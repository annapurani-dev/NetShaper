namespace NetShaper;

public sealed class Logger
{
    readonly object _l = new();
    string _dir;
    public string Dir => _dir;

    public Logger()
    {
        _dir = Path.Combine(AppContext.BaseDirectory, "logs");
        try { Directory.CreateDirectory(_dir); }
        catch
        {
            _dir = Path.Combine(Path.GetTempPath(), "NetShaperLogs");
            Directory.CreateDirectory(_dir);
        }
    }

    public void Log(string ev, string detail = "")
    {
        var now = DateTime.Now;
        var line = $"{now:yyyy-MM-dd HH:mm:ss.fff} | {ev,-14} | {detail}";
        lock (_l)
        {
            try { File.AppendAllText(Path.Combine(_dir, $"netshaper-{now:yyyyMMdd}.log"), line + Environment.NewLine); }
            catch { /* logging must never break shaping */ }
        }
    }
}
