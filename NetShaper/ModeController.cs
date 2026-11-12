namespace NetShaper;

/// <summary>
/// State machine: GOOD / FLUCTUATING as the base mode, with SPIKE_DOWN / SPIKE_UP overrides that expire
/// and fall back to the base mode, plus an optional scripted sequence. Ticks every 100 ms.
/// </summary>
public sealed class ModeController : IDisposable
{
    readonly ShapingEngine _engine;
    readonly Logger _log;
    readonly AppConfig _cfg;
    readonly object _l = new();
    readonly System.Threading.Timer _timer;
    readonly Fluctuator _fl = new();

    Settings _s = new();
    bool _running;
    Mode _base = Mode.Good;
    FluctProfile? _flOverride;

    bool _spike;
    Mode _spikeMode;
    Conditions _spikeCond = Conditions.Unshaped;
    long _spikeEnd;

    bool _seqActive, _seqLoop;
    string _seqName = "";
    List<SeqStep> _seqSteps = new();
    int _seqIdx;
    long _seqEnd;

    Mode _lastMode = Mode.Idle;

    public event Action<Status>? StatusChanged;

    public ModeController(ShapingEngine engine, Logger log, AppConfig cfg)
    {
        _engine = engine; _log = log; _cfg = cfg;
        _timer = new System.Threading.Timer(_ => { try { Tick(); } catch (Exception ex) { _log.Log("ERROR", "tick: " + ex.Message); } }, null, 100, 100);
    }

    public bool Running { get { lock (_l) return _running; } }
    public bool FluctuateOn { get { lock (_l) return _running && _base == Mode.Fluctuating; } }

    public void UpdateSettings(Settings s) { lock (_l) _s = s; }

    // ---------- commands ----------

    public void Start()
    {
        lock (_l)
        {
            if (_running) return;
            _engine.Start(_cfg.HotspotPrefix, _cfg.QueueMs);   // throws on driver / admin problems
            _running = true;
            _base = Mode.Good; _spike = false; _seqActive = false; _flOverride = null;
            _fl.Reset();
            _lastMode = Mode.Idle;
            _log.Log("START", "baseline " + Fmt(_s.Baseline));
        }
        Tick();
    }

    public void Stop()
    {
        lock (_l)
        {
            if (!_running) return;
            _running = false; _spike = false; _seqActive = false; _base = Mode.Good;
            _engine.Stop();
            _log.Log("STOP", "shaping stopped, network back to normal");
            _lastMode = Mode.Idle;
        }
        Tick();
    }

    public void SetFluctuate(bool on)
    {
        lock (_l)
        {
            if (!_running) return;
            CancelSequence("manual fluctuate toggle");
            _flOverride = null;
            if (on)
            {
                _base = Mode.Fluctuating; _fl.Reset();
                var p = _s.Fluct;
                _log.Log("FLUCTUATE_ON", $"kbps {p.MinKbps:0}-{p.MaxKbps:0} every {p.IntervalSec:0.#}s lat {p.LatencyMin:0}-{p.LatencyMax:0}ms jit {p.JitterMin:0}-{p.JitterMax:0}ms loss {p.LossMin:0.#}-{p.LossMax:0.#}% smooth={p.Smooth}");
            }
            else
            {
                _base = Mode.Good;
                _log.Log("FLUCTUATE_OFF", "back to baseline GOOD");
            }
        }
        Tick();
    }

    public void SpikeDown()
    {
        lock (_l)
        {
            if (!_running) return;
            CancelSequence("manual spike");
            int sec = Math.Max(1, _s.DurationSec);
            ApplySpike(Mode.SpikeDown, _s.SpikeDown, sec);
            _log.Log("SPIKE_DOWN", $"{sec}s | {Fmt(_s.SpikeDown)} | then back to {_base}");
        }
        Tick();
    }

    public void SpikeUp()
    {
        lock (_l)
        {
            if (!_running) return;
            CancelSequence("manual spike");
            int sec = Math.Max(1, _s.DurationSec);
            ApplySpike(Mode.SpikeUp, _s.SpikeUp, sec);
            _log.Log("SPIKE_UP", $"{sec}s | {Fmt(_s.SpikeUp)} | then back to {_base}");
        }
        Tick();
    }

    public bool StartSequence(string name)
    {
        lock (_l)
        {
            if (!_running || !_cfg.Sequences.TryGetValue(name, out var def) || def.Steps.Count == 0) return false;
            _seqName = name; _seqSteps = def.Steps; _seqLoop = def.Loop;
            _seqIdx = -1; _seqActive = true;
            _log.Log("SEQ_START", $"'{name}' steps={def.Steps.Count} loop={def.Loop}");
            AdvanceSequence(Environment.TickCount64);
        }
        Tick();
        return true;
    }

    public void StopSequence()
    {
        lock (_l)
        {
            if (!_seqActive) return;
            CancelSequence("stopped by user");
            _spike = false; _base = Mode.Good; _flOverride = null;
        }
        Tick();
    }

    // ---------- internals (call with lock held) ----------

    void CancelSequence(string why)
    {
        if (!_seqActive) return;
        _seqActive = false;
        _log.Log("SEQ_STOP", $"'{_seqName}' {why}");
    }

    void ApplySpike(Mode m, Conditions c, int sec)
    {
        _spike = true; _spikeMode = m; _spikeCond = c;
        _spikeEnd = Environment.TickCount64 + sec * 1000L;
    }

    void AdvanceSequence(long now)
    {
        _seqIdx++;
        if (_seqIdx >= _seqSteps.Count)
        {
            if (_seqLoop) _seqIdx = 0;
            else { _seqActive = false; _log.Log("SEQ_END", $"'{_seqName}' finished"); return; }
        }
        var st = _seqSteps[_seqIdx];
        _seqEnd = st.Seconds > 0 ? now + (long)(st.Seconds * 1000) : 0;
        switch (st.Mode.Trim().ToUpperInvariant())
        {
            case "GOOD":
                _base = Mode.Good; _spike = false; _flOverride = null;
                break;
            case "FLUCTUATE":
            case "FLUCTUATING":
                _base = Mode.Fluctuating; _spike = false; _fl.Reset();
                _flOverride = st.Profile != null && _cfg.Fluctuation.Profiles.TryGetValue(st.Profile, out var p) ? p : null;
                break;
            case "SPIKE_DOWN":
            {
                var c = _cfg.Spike.Down.TryGetValue(st.Strength ?? "hard", out var cc) ? cc : CondCfg.Of(64, 64, 300, 50, 10);
                int sec = st.Seconds > 0 ? (int)Math.Ceiling(st.Seconds) : _cfg.Spike.DefaultDurationSec;
                ApplySpike(Mode.SpikeDown, c.ToConditions(), sec);
                if (_seqEnd == 0) _seqEnd = _spikeEnd;
                break;
            }
            case "SPIKE_UP":
            {
                var c = _cfg.Spike.Up.TryGetValue(st.Strength ?? "full", out var cc) ? cc : CondCfg.Of(0, 0, 0, 0, 0);
                int sec = st.Seconds > 0 ? (int)Math.Ceiling(st.Seconds) : _cfg.Spike.DefaultDurationSec;
                ApplySpike(Mode.SpikeUp, c.ToConditions(), sec);
                if (_seqEnd == 0) _seqEnd = _spikeEnd;
                break;
            }
            default:
                _log.Log("SEQ_WARN", $"unknown mode '{st.Mode}' in sequence '{_seqName}'");
                break;
        }
        _log.Log("SEQ_STEP", $"'{_seqName}' {_seqIdx + 1}/{_seqSteps.Count} {st.Mode} {st.Seconds}s {st.Strength ?? st.Profile ?? ""}");
    }

    static string K(double k) => k <= 0 ? "unlimited" : $"{k:0}Kbps";
    static string Fmt(Conditions c) => $"up={K(c.UpKbps)} down={K(c.DownKbps)} lat={c.LatencyMs:0}ms jitter={c.JitterMs:0}ms loss={c.LossPct:0.#}%";

    void Tick()
    {
        Status st;
        lock (_l)
        {
            if (!_running)
            {
                st = new Status(Mode.Idle, Conditions.Unshaped, 0, "", false, 0, 0);
            }
            else
            {
                long now = Environment.TickCount64;
                if (_seqActive && _seqEnd > 0 && now >= _seqEnd) AdvanceSequence(now);
                if (_spike && now >= _spikeEnd)
                {
                    _spike = false;
                    _log.Log("SPIKE_END", $"returning to {_base}");
                }

                Mode m; Conditions c;
                if (_spike) { m = _spikeMode; c = _spikeCond; }
                else if (_base == Mode.Fluctuating) { m = Mode.Fluctuating; c = _fl.Evaluate(now, _flOverride ?? _s.Fluct, _s.Baseline); }
                else { m = Mode.Good; c = _s.Baseline; }

                _engine.SetConditions(c);
                if (m != _lastMode) { _log.Log("MODE", $"{_lastMode} -> {m} | {Fmt(c)}"); _lastMode = m; }

                double left = _spike ? (_spikeEnd - now) / 1000.0
                            : (_seqActive && _seqEnd > 0 ? (_seqEnd - now) / 1000.0 : 0);
                string detail = _seqActive ? $"Sequence '{_seqName}' step {_seqIdx + 1}/{_seqSteps.Count}" : "";
                st = new Status(m, c, Math.Max(0, left), detail, true, _engine.Passed, _engine.Dropped);
            }
        }
        StatusChanged?.Invoke(st);
    }

    public void Dispose()
    {
        _timer.Dispose();
        lock (_l) { _running = false; _engine.Stop(); }
    }
}
