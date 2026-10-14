param([int]$Ticks = -3)
Add-Type @"
using System; using System.Runtime.InteropServices;
public class M { [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out R r);
  [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
  [DllImport("user32.dll")] public static extern void mouse_event(uint f, uint dx, uint dy, int data, UIntPtr e);
  [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
  public struct R { public int L, T, Rt, B; } }
"@
[M]::SetProcessDPIAware() | Out-Null
$p = Get-Process | Where-Object { $_.MainWindowTitle -like "Minecraft*" } | Select-Object -First 1
$r = New-Object M+R; [M]::GetWindowRect($p.MainWindowHandle, [ref]$r) | Out-Null
[M]::SetCursorPos([int](($r.L + $r.Rt) / 2), [int]($r.T + ($r.B - $r.T) * 0.45)) | Out-Null
Start-Sleep -Milliseconds 300
for ($i = 0; $i -lt [Math]::Abs($Ticks); $i++) { [M]::mouse_event(0x0800, 0, 0, [Math]::Sign($Ticks) * 120, [UIntPtr]::Zero); Start-Sleep -Milliseconds 120 }
"scrolled"
