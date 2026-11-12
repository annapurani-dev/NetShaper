using System.Runtime.InteropServices;

namespace NetShaper;

/// <summary>WINDIVERT_ADDRESS (WinDivert 2.x) - 80 bytes. Only the fields we need are exposed.</summary>
[StructLayout(LayoutKind.Sequential, Size = 80)]
public struct WinDivertAddress
{
    public long Timestamp;
    public uint Flags;      // Layer:8 Event:8 Sniffed:1 Outbound:1 Loopback:1 Impostor:1 IPv6:1 IPChecksum:1 TCPChecksum:1 UDPChecksum:1
    public uint Reserved2;
    public uint IfIdx;
    public uint SubIfIdx;

    public readonly bool Outbound => ((Flags >> 17) & 1) != 0;
}

internal static class WinDivert
{
    const string Dll = "WinDivert.dll";

    public enum Layer : int { Network = 0, NetworkForward = 1 }

    [DllImport(Dll, EntryPoint = "WinDivertOpen", SetLastError = true, CharSet = CharSet.Ansi)]
    public static extern IntPtr Open([MarshalAs(UnmanagedType.LPStr)] string filter, Layer layer, short priority, ulong flags);

    [DllImport(Dll, EntryPoint = "WinDivertRecv", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static extern bool Recv(IntPtr handle, byte[] packet, uint packetLen, out uint recvLen, ref WinDivertAddress addr);

    [DllImport(Dll, EntryPoint = "WinDivertSend", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static extern bool Send(IntPtr handle, byte[] packet, uint packetLen, out uint sendLen, ref WinDivertAddress addr);

    /// <summary>how: 1 = RECV, 2 = SEND, 3 = BOTH</summary>
    [DllImport(Dll, EntryPoint = "WinDivertShutdown", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static extern bool Shutdown(IntPtr handle, int how);

    [DllImport(Dll, EntryPoint = "WinDivertClose", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static extern bool Close(IntPtr handle);
}

internal static class WinMm
{
    [DllImport("winmm.dll")] public static extern uint timeBeginPeriod(uint ms);
    [DllImport("winmm.dll")] public static extern uint timeEndPeriod(uint ms);
}
