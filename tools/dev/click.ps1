param([int]$X, [int]$Y, [switch]$Hover, [string]$Title = "Minecraft")
# X, Y: pixels inside the Minecraft window, as in shot.ps1's screenshot.
Add-Type @"
using System; using System.Runtime.InteropServices;
public class C { [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern IntPtr FindWindow(string c, string t);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out R r);
  [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern void mouse_event(uint f, uint dx, uint dy, int data, UIntPtr e);
  [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
  public struct R { public int L, T, Rt, B; } }
"@
[C]::SetProcessDPIAware() | Out-Null
$h = [C]::FindWindow([NullString]::Value, $Title)
if ($h -eq [IntPtr]::Zero) { $p = Get-Process | Where-Object { $_.MainWindowTitle -like "$Title*" } | Select-Object -First 1; if ($p) { $h = $p.MainWindowHandle } }
[C]::SetForegroundWindow($h) | Out-Null
$r = New-Object C+R; [C]::GetWindowRect($h, [ref]$r) | Out-Null
[C]::SetCursorPos($r.L + $X, $r.T + $Y) | Out-Null
Start-Sleep -Milliseconds 400
if (-not $Hover) { [C]::mouse_event(0x0002, 0, 0, 0, [UIntPtr]::Zero); Start-Sleep -Milliseconds 60; [C]::mouse_event(0x0004, 0, 0, 0, [UIntPtr]::Zero) }
"done"
