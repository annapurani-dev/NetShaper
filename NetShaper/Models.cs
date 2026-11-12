using System.Text.Json;
using System.Text.Json.Serialization;

namespace NetShaper;

public enum Mode { Idle, Good, Fluctuating, SpikeDown, SpikeUp }

/// <summary>Effective network conditions. Kbps of 0 means "unlimited".</summary>
public sealed record Conditions(double UpKbps, double DownKbps, double LatencyMs, double JitterMs, double LossPct)
{
    public static readonly Conditions Unshaped = new(0, 0, 0, 0, 0);
}

public sealed record Status(Mode Mode, Conditions Cond, double TimeLeftSec, string Detail, bool Running, long Passed, long Dropped);

public sealed class CondCfg
{
    public double UpKbps { get; set; }
    public double DownKbps { get; set; }
    public double LatencyMs { get; set; }
    public double JitterMs { get; set; }
    public double LossPct { get; set; }
    public Conditions ToConditions() => new(UpKbps, DownKbps, LatencyMs, JitterMs, LossPct);
    public static CondCfg Of(double up, double down, double lat, double jit, double loss) =>
        new() { UpKbps = up, DownKbps = down, LatencyMs = lat, JitterMs = jit, LossPct = loss };
}

public sealed class FluctProfile
{
    public double MinKbps { get; set; } = 500;
    public double MaxKbps { get; set; } = 4000;
    public double IntervalSec { get; set; } = 2;
    public double LatencyMin { get; set; } = 40;
    public double LatencyMax { get; set; } = 200;
    public double JitterMin { get; set; } = 10;
    public double JitterMax { get; set; } = 60;
    public double LossMin { get; set; } = 0;
    public double LossMax { get; set; } = 2;
    /// <summary>true = ramp smoothly between targets; false = jump (step) at each interval.</summary>
    public bool Smooth { get; set; } = true;
}

public sealed class FluctCfg
{
    public string Default { get; set; } = "medium";
    public Dictionary<string, FluctProfile> Profiles { get; set; } = new(StringComparer.OrdinalIgnoreCase);
}

public sealed class SpikeCfg
{
    public int DefaultDurationSec { get; set; } = 5;
    public List<int> DurationsSec { get; set; } = new() { 3, 5, 10, 30 };
    public Dictionary<string, CondCfg> Down { get; set; } = new(StringComparer.OrdinalIgnoreCase);
    public Dictionary<string, CondCfg> Up { get; set; } = new(StringComparer.OrdinalIgnoreCase);
}

public sealed class SeqStep
{
    /// <summary>GOOD | FLUCTUATE | SPIKE_DOWN | SPIKE_UP</summary>
    [JsonPropertyName("mode")] public string Mode { get; set; } = "GOOD";
    /// <summary>Step length in seconds. 0 = stay in this step forever (only sensible for the last step).</summary>
    [JsonPropertyName("seconds")] public double Seconds { get; set; }
    [JsonPropertyName("strength")] public string? Strength { get; set; }
    [JsonPropertyName("profile")] public string? Profile { get; set; }
}

public sealed class SeqDef
{
    [JsonPropertyName("loop")] public bool Loop { get; set; }
    [JsonPropertyName("steps")] public List<SeqStep> Steps { get; set; } = new();
}

public sealed class AppConfig
{
    /// <summary>First octets of the PC hotspot subnet (Windows default 192.168.137.x). Used to tell phone upload from download.</summary>
    public string HotspotPrefix { get; set; } = "192.168.137";
    /// <summary>Simulated buffer size. Packets that would wait longer than this are dropped (tail drop).</summary>
    public int QueueMs { get; set; } = 400;
    public CondCfg Baseline { get; set; } = CondCfg.Of(5000, 5000, 20, 5, 0);
    public FluctCfg Fluctuation { get; set; } = new();
    public SpikeCfg Spike { get; set; } = new();
    public Dictionary<string, SeqDef> Sequences { get; set; } = new(StringComparer.OrdinalIgnoreCase);

    static readonly JsonSerializerOptions Opt = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        PropertyNameCaseInsensitive = true,
        ReadCommentHandling = JsonCommentHandling.Skip,
        AllowTrailingCommas = true,
        WriteIndented = true
    };

    public static AppConfig Load(string path, Logger log)
    {
        try
        {
            if (!File.Exists(path))
            {
                var d = Default();
                File.WriteAllText(path, JsonSerializer.Serialize(d, Opt));
                log.Log("CONFIG", "config.json missing - wrote defaults");
                return d;
            }
            var cfg = JsonSerializer.Deserialize<AppConfig>(File.ReadAllText(path), Opt) ?? Default();
            cfg.Normalize();
            log.Log("CONFIG", "loaded " + path);
            return cfg;
        }
        catch (Exception ex)
        {
            log.Log("CONFIG_ERROR", ex.Message + " - using built-in defaults");
            return Default();
        }
    }

    void Normalize()
    {
        Fluctuation.Profiles = new(Fluctuation.Profiles, StringComparer.OrdinalIgnoreCase);
        Spike.Down = new(Spike.Down, StringComparer.OrdinalIgnoreCase);
        Spike.Up = new(Spike.Up, StringComparer.OrdinalIgnoreCase);
        Sequences = new(Sequences, StringComparer.OrdinalIgnoreCase);
        if (Fluctuation.Profiles.Count == 0) Fluctuation.Profiles["medium"] = new FluctProfile();
        if (Spike.Down.Count == 0) Spike.Down["hard"] = CondCfg.Of(64, 64, 300, 50, 10);
        if (Spike.Up.Count == 0) Spike.Up["full"] = CondCfg.Of(0, 0, 0, 0, 0);
        if (Spike.DurationsSec.Count == 0) Spike.DurationsSec = new() { 3, 5, 10, 30 };
    }

    public static AppConfig Default()
    {
        var c = new AppConfig();
        c.Fluctuation.Profiles["mild"] = new FluctProfile { MinKbps = 2500, MaxKbps = 5000, IntervalSec = 3, LatencyMin = 20, LatencyMax = 60, JitterMin = 5, JitterMax = 20, LossMin = 0, LossMax = 0.5, Smooth = true };
        c.Fluctuation.Profiles["medium"] = new FluctProfile();
        c.Fluctuation.Profiles["aggressive"] = new FluctProfile { MinKbps = 64, MaxKbps = 3000, IntervalSec = 1, LatencyMin = 50, LatencyMax = 500, JitterMin = 20, JitterMax = 150, LossMin = 0, LossMax = 8, Smooth = false };
        c.Spike.Down["mild"] = CondCfg.Of(300, 300, 100, 20, 2);
        c.Spike.Down["hard"] = CondCfg.Of(64, 64, 300, 50, 10);
        c.Spike.Down["blackout"] = CondCfg.Of(8, 8, 800, 100, 50);
        c.Spike.Up["mild"] = CondCfg.Of(8000, 8000, 10, 2, 0);
        c.Spike.Up["full"] = CondCfg.Of(0, 0, 0, 0, 0);
        c.Sequences["demo"] = new SeqDef
        {
            Steps = new()
            {
                new SeqStep { Mode = "GOOD", Seconds = 20 },
                new SeqStep { Mode = "FLUCTUATE", Seconds = 30, Profile = "medium" },
                new SeqStep { Mode = "SPIKE_DOWN", Seconds = 10, Strength = "hard" },
                new SeqStep { Mode = "SPIKE_UP", Seconds = 5, Strength = "full" },
                new SeqStep { Mode = "FLUCTUATE", Seconds = 0, Profile = "mild" },
            }
        };
        return c;
    }
}

/// <summary>Live values taken from the UI.</summary>
public sealed class Settings
{
    public Conditions Baseline { get; set; } = new(5000, 5000, 20, 5, 0);
    public FluctProfile Fluct { get; set; } = new();
    public Conditions SpikeDown { get; set; } = new(64, 64, 300, 50, 10);
    public Conditions SpikeUp { get; set; } = new(0, 0, 0, 0, 0);
    public int DurationSec { get; set; } = 5;
}
