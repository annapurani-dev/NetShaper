using System.ComponentModel;
using System.Diagnostics;
using System.Runtime.InteropServices;

namespace NetShaper;

/// <summary>
/// Captures real packets with WinDivert, delays/drops/rate-limits them according to the current
/// Conditions, and re-injects them. Two handles: NETWORK (this PC's own traffic) and
/// NETWORK_FORWARD (traffic routed through the PC, i.e. the phone on the Mobile Hotspot).
/// When the process exits for any reason the kernel closes the handles and traffic flows normally.
/// </summary>
public sealed class ShapingEngine : IDisposable
{
    static readonly IntPtr Invalid = new(-1);

    readonly Logger _log;
    volatile Conditions _cond = Conditions.Unshaped;
    Pipe? _up, _down;
    IntPtr _hNet = Invalid, _hFwd = Invalid;
    Thread? _tNet, _tFwd;
    byte[] _prefix = Array.Empty<byte>();
    volatile bool _running;

    public ShapingEngine(Logger log) => _log = log;

    public bool IsRunning => _running;
    public long Passed => (_up?.Passed ?? 0) + (_down?.Passed ?? 0);
    public long Dropped => (_up?.Dropped ?? 0) + (_down?.Dropped ?? 0);

    public void SetConditions(Conditions c) => _cond = c;

    public void Start(string hotspotPrefix, int queueMs)
    {
        if (_running) return;
        _prefix = ParsePrefix(hotspotPrefix);
        WinMm.timeBeginPeriod(1);
        try
        {
            _hNet = OpenHandle(WinDivert.Layer.Network);
            try { _hFwd = OpenHandle(WinDivert.Layer.NetworkForward); }
            catch (Exception ex)
            {
                _hFwd = Invalid;
                _log.Log("WARN", "Forward layer unavailable (hotspot traffic will NOT be shaped): " + ex.Message);
            }
        }
        catch
        {
            if (_hNet != Invalid) { WinDivert.Close(_hNet); _hNet = Invalid; }
            WinMm.timeEndPeriod(1);
            throw;
        }

        _up = new Pipe(queueMs, "up");
        _down = new Pipe(queueMs, "down");
        _running = true;

        _tNet = new Thread(() => RecvLoop(_hNet, false)) { IsBackground = true, Name = "recv-net" };
        _tNet.Start();
        if (_hFwd != Invalid)
        {
            _tFwd = new Thread(() => RecvLoop(_hFwd, true)) { IsBackground = true, Name = "recv-fwd" };
            _tFwd.Start();
        }
        _log.Log("ENGINE_START", $"forward={(_hFwd != Invalid)} hotspotPrefix={hotspotPrefix} queueMs={queueMs}");
    }

    public void Stop()
    {
        if (!_running) return;
        _running = false;
        if (_hNet != Invalid) WinDivert.Shutdown(_hNet, 1);
        if (_hFwd != Invalid) WinDivert.Shutdown(_hFwd, 1);
        _tNet?.Join(1500);
        _tFwd?.Join(1500);
        _up?.Stop();      // flushes anything still queued
        _down?.Stop();
        if (_hNet != Invalid) WinDivert.Close(_hNet);
        if (_hFwd != Invalid) WinDivert.Close(_hFwd);
        _hNet = _hFwd = Invalid;
        WinMm.timeEndPeriod(1);
        _log.Log("ENGINE_STOP", $"passed={Passed} dropped={Dropped}");
    }

    public void Dispose() => Stop();

    static IntPtr OpenHandle(WinDivert.Layer layer)
    {
        IntPtr h;
        try { h = WinDivert.Open("!loopback", layer, 0, 0); }
        catch (DllNotFoundException)
        {
            throw new InvalidOperationException("WinDivert.dll was not found next to NetShaper.exe. Run get-windivert.ps1 and rebuild (see README).");
        }
        catch (BadImageFormatException)
        {
            throw new InvalidOperationException("WinDivert.dll has the wrong architecture. Use the x64 build.");
        }
        if (h == Invalid)
        {
            int err = Marshal.GetLastWin32Error();
            throw new Win32Exception(err, err switch
            {
                5 => "Access denied - run NetShaper as Administrator.",
                2 or 3 => "WinDivert64.sys not found next to NetShaper.exe (see README).",
                577 => "Windows rejected the WinDivert driver signature (Secure Boot / HVCI / policy).",
                1275 => "The WinDivert driver was blocked by security software or policy.",
                1060 => "WinDivert driver service missing - reinstall WinDivert files.",
                _ => $"WinDivertOpen failed (Win32 error {err})."
            });
        }
        return h;
    }

    static byte[] ParsePrefix(string s)
    {
        var parts = (s ?? "").Split('/')[0].Split('.', StringSplitOptions.RemoveEmptyEntries);
        var list = new List<byte>();
        foreach (var p in parts) if (byte.TryParse(p, out var b)) list.Add(b); else break;
        if (list.Count == 4 && list[3] == 0) list.RemoveAt(3);
        return list.ToArray();
    }

    bool PrefixMatch(byte[] d, int off)
    {
        if (_prefix.Length == 0 || d.Length < off + _prefix.Length) return false;
        for (int i = 0; i < _prefix.Length; i++) if (d[off + i] != _prefix[i]) return false;
        return true;
    }

    bool IsUpload(bool forward, in WinDivertAddress addr, byte[] d)
    {
        if (!forward) return addr.Outbound;
        // Forwarded (hotspot) traffic: phone -> internet has a hotspot-subnet source.
        if (d.Length >= 20 && (d[0] >> 4) == 4)
        {
            if (PrefixMatch(d, 12)) return true;   // src in hotspot subnet  = phone upload
            if (PrefixMatch(d, 16)) return false;  // dst in hotspot subnet  = phone download
        }
        return addr.Outbound;
    }

    void RecvLoop(IntPtr h, bool forward)
    {
        var buf = new byte[65535 + 128];
        var addr = new WinDivertAddress();
        while (_running)
        {
            try
            {
                if (!WinDivert.Recv(h, buf, (uint)buf.Length, out uint n, ref addr))
                {
                    if (!_running) break;
                    int err = Marshal.GetLastWin32Error();
                    if (err == 232 || err == 6) break;   // handle shut down / closed
                    Thread.Sleep(1);
                    continue;
                }
                var data = new byte[n];
                Buffer.BlockCopy(buf, 0, data, 0, (int)n);
                bool up = IsUpload(forward, in addr, data);
                (up ? _up! : _down!).Enqueue(new Item(h, addr, data), _cond, up);
            }
            catch (Exception ex)
            {
                _log.Log("ERROR", "recv: " + ex.Message);
                Thread.Sleep(5);
            }
        }
    }

    sealed class Item
    {
        public readonly IntPtr Handle;
        public WinDivertAddress Addr;
        public readonly byte[] Data;
        public double Rel;
        public Item(IntPtr h, WinDivertAddress a, byte[] d) { Handle = h; Addr = a; Data = d; }
    }

    /// <summary>One direction of the simulated link: loss, serialization delay (rate limit), latency + jitter, bounded buffer.</summary>
    sealed class Pipe
    {
        readonly Queue<Item> _q = new();
        readonly object _l = new();
        readonly Random _rng = new();
        readonly double _queueMs;
        readonly Thread _t;
        double _linkFree, _lastRel;
        bool _stop;
        long _passed, _dropped;
        public long Passed => Interlocked.Read(ref _passed);
        public long Dropped => Interlocked.Read(ref _dropped);

        static double Now() => Stopwatch.GetTimestamp() * 1000.0 / Stopwatch.Frequency;

        public Pipe(int queueMs, string name)
        {
            _queueMs = Math.Max(20, queueMs);
            _t = new Thread(Run) { IsBackground = true, Name = "pipe-" + name, Priority = ThreadPriority.AboveNormal };
            _t.Start();
        }

        public void Enqueue(Item it, Conditions c, bool up)
        {
            lock (_l)
            {
                if (c.LossPct > 0 && _rng.NextDouble() * 100.0 < c.LossPct) { Interlocked.Increment(ref _dropped); return; }

                double now = Now();
                double kbps = up ? c.UpKbps : c.DownKbps;
                double rel;
                if (kbps > 0)
                {
                    double start = Math.Max(now, _linkFree);
                    if (start - now > _queueMs || _q.Count > 20000) { Interlocked.Increment(ref _dropped); return; }
                    _linkFree = start + it.Data.Length * 8.0 / kbps;   // bits / kbps = ms
                    rel = _linkFree;
                }
                else { _linkFree = now; rel = now; }

                rel += c.LatencyMs + (c.JitterMs > 0 ? _rng.NextDouble() * c.JitterMs : 0);
                if (rel < _lastRel) rel = _lastRel;   // keep packet order
                _lastRel = rel;
                it.Rel = rel;
                _q.Enqueue(it);
                Monitor.Pulse(_l);
            }
        }

        void Run()
        {
            while (true)
            {
                Item it;
                lock (_l)
                {
                    while (true)
                    {
                        if (_q.Count == 0)
                        {
                            if (_stop) return;
                            Monitor.Wait(_l);
                            continue;
                        }
                        double wait = _q.Peek().Rel - Now();
                        if (wait <= 0.5 || _stop) { it = _q.Dequeue(); break; }
                        Monitor.Wait(_l, (int)Math.Min(50, Math.Ceiling(wait)));
                    }
                }
                try
                {
                    WinDivert.Send(it.Handle, it.Data, (uint)it.Data.Length, out _, ref it.Addr);
                    Interlocked.Increment(ref _passed);
                }
                catch { /* ignore, keep forwarding */ }
            }
        }

        public void Stop()
        {
            lock (_l) { _stop = true; Monitor.PulseAll(_l); }
            _t.Join(3000);
        }
    }
}
