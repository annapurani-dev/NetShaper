using System.Diagnostics;
using System.Globalization;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Threading;

namespace NetShaper;

public partial class MainWindow : Window
{
    readonly Logger _log;
    readonly AppConfig _cfg;
    readonly ShapingEngine _engine;
    readonly ModeController _ctl;
    readonly DispatcherTimer _push;
    CondCfg _downPreset = CondCfg.Of(64, 64, 300, 50, 10);
    CondCfg _upPreset = CondCfg.Of(0, 0, 0, 0, 0);
    bool _smooth = true;
    bool _loading = true;

    public MainWindow()
    {
        InitializeComponent();
        _log = new Logger();
        _cfg = AppConfig.Load(System.IO.Path.Combine(AppContext.BaseDirectory, "config.json"), _log);
        _engine = new ShapingEngine(_log);
        _ctl = new ModeController(_engine, _log, _cfg);

        // Safety nets: always release the packet capture on exit / crash.
        AppDomain.CurrentDomain.ProcessExit += (_, _) => SafeStop();
        AppDomain.CurrentDomain.UnhandledException += (_, _) => SafeStop();
        Application.Current.DispatcherUnhandledException += (_, e) =>
        {
            _log.Log("ERROR", "UI exception: " + e.Exception.Message);
            e.Handled = true;
        };

        // Baseline from config
        BaseUp.Text = _cfg.Baseline.UpKbps.ToString(CultureInfo.InvariantCulture);
        BaseDown.Text = _cfg.Baseline.DownKbps.ToString(CultureInfo.InvariantCulture);
        BaseLat.Text = _cfg.Baseline.LatencyMs.ToString(CultureInfo.InvariantCulture);
        BaseJit.Text = _cfg.Baseline.JitterMs.ToString(CultureInfo.InvariantCulture);
        BaseLoss.Text = _cfg.Baseline.LossPct.ToString(CultureInfo.InvariantCulture);

        ProfileBox.ItemsSource = _cfg.Fluctuation.Profiles.Keys.ToList();
        DownStrength.ItemsSource = _cfg.Spike.Down.Keys.ToList();
        UpStrength.ItemsSource = _cfg.Spike.Up.Keys.ToList();
        DurPreset.ItemsSource = _cfg.Spike.DurationsSec;
        SeqBox.ItemsSource = _cfg.Sequences.Keys.ToList();
        DurBox.Text = _cfg.Spike.DefaultDurationSec.ToString();

        ProfileBox.SelectionChanged += (_, _) => LoadProfile();
        DownStrength.SelectionChanged += (_, _) => LoadDownPreset();
        UpStrength.SelectionChanged += (_, _) => LoadUpPreset();
        DurPreset.SelectionChanged += (_, _) => { if (DurPreset.SelectedItem != null) DurBox.Text = DurPreset.SelectedItem.ToString(); };

        ProfileBox.SelectedItem = _cfg.Fluctuation.Profiles.ContainsKey(_cfg.Fluctuation.Default) ? _cfg.Fluctuation.Default : _cfg.Fluctuation.Profiles.Keys.First();
        DownStrength.SelectedItem = _cfg.Spike.Down.ContainsKey("hard") ? "hard" : _cfg.Spike.Down.Keys.First();
        UpStrength.SelectedItem = _cfg.Spike.Up.ContainsKey("full") ? "full" : _cfg.Spike.Up.Keys.First();
        if (SeqBox.Items.Count > 0) SeqBox.SelectedIndex = 0;
        _loading = false;

        // Push UI values to the controller a few times per second so edits apply live.
        _push = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(300) };
        _push.Tick += (_, _) => PushSettings();
        _push.Start();
        PushSettings();

        _ctl.StatusChanged += s => Dispatcher.BeginInvoke(new Action(() => Render(s)));
        Closed += (_, _) => { _push.Stop(); _ctl.Dispose(); };
        Render(new Status(Mode.Idle, Conditions.Unshaped, 0, "", false, 0, 0));
    }

    void SafeStop() { try { _engine.Stop(); } catch { } }

    // ---------- UI -> settings ----------

    static double D(TextBox t, double def)
    {
        var s = t.Text.Trim().Replace(',', '.');
        return double.TryParse(s, NumberStyles.Float, CultureInfo.InvariantCulture, out var v) && v >= 0 ? v : def;
    }

    void LoadProfile()
    {
        if (ProfileBox.SelectedItem is not string name || !_cfg.Fluctuation.Profiles.TryGetValue(name, out var p)) return;
        FMin.Text = p.MinKbps.ToString(CultureInfo.InvariantCulture);
        FMax.Text = p.MaxKbps.ToString(CultureInfo.InvariantCulture);
        FInt.Text = p.IntervalSec.ToString(CultureInfo.InvariantCulture);
        FLatMin.Text = p.LatencyMin.ToString(CultureInfo.InvariantCulture);
        FLatMax.Text = p.LatencyMax.ToString(CultureInfo.InvariantCulture);
        FJitMin.Text = p.JitterMin.ToString(CultureInfo.InvariantCulture);
        FJitMax.Text = p.JitterMax.ToString(CultureInfo.InvariantCulture);
        FLossMin.Text = p.LossMin.ToString(CultureInfo.InvariantCulture);
        FLossMax.Text = p.LossMax.ToString(CultureInfo.InvariantCulture);
        _smooth = p.Smooth;
    }

    void LoadDownPreset()
    {
        if (DownStrength.SelectedItem is not string n || !_cfg.Spike.Down.TryGetValue(n, out var c)) return;
        _downPreset = c;
        DownKbps.Text = c.DownKbps.ToString(CultureInfo.InvariantCulture);
        DownLoss.Text = c.LossPct.ToString(CultureInfo.InvariantCulture);
    }

    void LoadUpPreset()
    {
        if (UpStrength.SelectedItem is not string n || !_cfg.Spike.Up.TryGetValue(n, out var c)) return;
        _upPreset = c;
        UpKbps.Text = c.DownKbps.ToString(CultureInfo.InvariantCulture);
    }

    Settings BuildSettings()
    {
        double fmin = D(FMin, 500), fmax = D(FMax, 4000);
        if (fmax < fmin) (fmin, fmax) = (fmax, fmin);
        double dk = D(DownKbps, _downPreset.DownKbps), uk = D(UpKbps, _upPreset.DownKbps);
        return new Settings
        {
            Baseline = new Conditions(D(BaseUp, 5000), D(BaseDown, 5000), D(BaseLat, 20), D(BaseJit, 5), Math.Min(100, D(BaseLoss, 0))),
            Fluct = new FluctProfile
            {
                MinKbps = fmin, MaxKbps = fmax, IntervalSec = Math.Max(0.2, D(FInt, 2)),
                LatencyMin = D(FLatMin, 40), LatencyMax = D(FLatMax, 200),
                JitterMin = D(FJitMin, 10), JitterMax = D(FJitMax, 60),
                LossMin = D(FLossMin, 0), LossMax = Math.Min(100, D(FLossMax, 2)),
                Smooth = _smooth
            },
            SpikeDown = new Conditions(dk, dk, _downPreset.LatencyMs, _downPreset.JitterMs, Math.Min(100, D(DownLoss, _downPreset.LossPct))),
            SpikeUp = new Conditions(uk, uk, _upPreset.LatencyMs, _upPreset.JitterMs, _upPreset.LossPct),
            DurationSec = (int)Math.Max(1, D(DurBox, 5))
        };
    }

    void PushSettings() { if (!_loading) _ctl.UpdateSettings(BuildSettings()); }

    // ---------- buttons ----------

    void StartStop_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            PushSettings();
            if (_ctl.Running) _ctl.Stop(); else _ctl.Start();
        }
        catch (Exception ex)
        {
            _log.Log("ERROR", "start: " + ex.Message);
            MessageBox.Show(ex.Message, "Could not start shaping", MessageBoxButton.OK, MessageBoxImage.Error);
        }
    }

    void Fluct_Click(object sender, RoutedEventArgs e) { PushSettings(); _ctl.SetFluctuate(!_ctl.FluctuateOn); }
    void SpikeDown_Click(object sender, RoutedEventArgs e) { PushSettings(); _ctl.SpikeDown(); }
    void SpikeUp_Click(object sender, RoutedEventArgs e) { PushSettings(); _ctl.SpikeUp(); }

    void SeqStart_Click(object sender, RoutedEventArgs e)
    {
        if (SeqBox.SelectedItem is string n) _ctl.StartSequence(n);
    }

    void SeqStop_Click(object sender, RoutedEventArgs e) => _ctl.StopSequence();

    void OpenLogs_Click(object sender, RoutedEventArgs e)
    {
        try { Process.Start(new ProcessStartInfo("explorer.exe", _log.Dir) { UseShellExecute = true }); } catch { }
    }

    // ---------- status ----------

    static string K(double k) => k <= 0 ? "unlimited" : $"{k:N0} Kbps";

    void Render(Status s)
    {
        var (text, color) = s.Mode switch
        {
            Mode.Good => ("GOOD", Color.FromRgb(0x16, 0xA3, 0x4A)),
            Mode.Fluctuating => ("FLUCTUATING", Color.FromRgb(0xEA, 0x58, 0x0C)),
            Mode.SpikeDown => ("SPIKE_DOWN", Color.FromRgb(0xDC, 0x26, 0x26)),
            Mode.SpikeUp => ("SPIKE_UP", Color.FromRgb(0x25, 0x63, 0xEB)),
            _ => ("IDLE", Color.FromRgb(0x6B, 0x72, 0x80)),
        };
        ModeText.Text = text;
        StatusBorder.Background = new SolidColorBrush(color);

        if (s.Running)
        {
            var c = s.Cond;
            NumsText.Text = $"Up {K(c.UpKbps)}  |  Down {K(c.DownKbps)}  |  Latency {c.LatencyMs:0} ms (jitter up to {c.JitterMs:0})  |  Loss {c.LossPct:0.0}%";
            var parts = new List<string>();
            if (s.TimeLeftSec > 0) parts.Add($"Time left: {s.TimeLeftSec:0.0}s");
            if (s.Detail.Length > 0) parts.Add(s.Detail);
            parts.Add($"Packets passed {s.Passed:N0} / dropped {s.Dropped:N0}");
            InfoText.Text = string.Join("   |   ", parts);
        }
        else
        {
            NumsText.Text = "Not shaping - network is normal";
            InfoText.Text = "";
        }

        StartStopBtn.Content = s.Running ? "Stop shaping" : "Start shaping";
        FluctBtn.Content = (s.Running && _ctl.FluctuateOn) ? "Fluctuate: ON (click to stop)" : "Fluctuate: OFF";
        foreach (var b in new[] { FluctBtn, SpikeDownBtn, SpikeUpBtn, SeqStartBtn, SeqStopBtn }) b.IsEnabled = s.Running;
    }
}
