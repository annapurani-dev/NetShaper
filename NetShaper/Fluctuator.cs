namespace NetShaper;

/// <summary>Produces continuously changing conditions: picks new random targets every interval and ramps (or steps) to them.</summary>
public sealed class Fluctuator
{
    readonly Random _r = new();
    bool _init;
    long _segStart, _segEnd;
    Conditions _from = Conditions.Unshaped, _to = Conditions.Unshaped, _cur = Conditions.Unshaped;

    public void Reset() => _init = false;

    double U(double a, double b)
    {
        if (b < a) (a, b) = (b, a);
        return a + _r.NextDouble() * (b - a);
    }

    static double L(double a, double b, double t) => a + (b - a) * t;

    public Conditions Evaluate(long now, FluctProfile p, Conditions start)
    {
        if (!_init)
        {
            _cur = _from = _to = start;
            _segStart = _segEnd = now;
            _init = true;
        }
        if (now >= _segEnd)
        {
            _from = _cur;
            long len = (long)Math.Max(200, p.IntervalSec * 1000);
            _segStart = now;
            _segEnd = now + len;
            _to = new Conditions(
                Math.Max(8, U(p.MinKbps, p.MaxKbps)),
                Math.Max(8, U(p.MinKbps, p.MaxKbps)),
                Math.Max(0, U(p.LatencyMin, p.LatencyMax)),
                Math.Max(0, U(p.JitterMin, p.JitterMax)),
                Math.Clamp(U(p.LossMin, p.LossMax), 0, 100));
            if (!p.Smooth) _from = _to;
        }
        double t = _segEnd > _segStart ? Math.Clamp((now - _segStart) / (double)(_segEnd - _segStart), 0, 1) : 1;
        _cur = new Conditions(
            L(_from.UpKbps, _to.UpKbps, t),
            L(_from.DownKbps, _to.DownKbps, t),
            L(_from.LatencyMs, _to.LatencyMs, t),
            L(_from.JitterMs, _to.JitterMs, t),
            L(_from.LossPct, _to.LossPct, t));
        return _cur;
    }
}
