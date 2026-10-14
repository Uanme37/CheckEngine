param([string]$Out, [string]$Title = "Minecraft")
Add-Type -AssemblyName System.Drawing
Add-Type @"
using System; using System.Runtime.InteropServices;
public class W { [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern IntPtr FindWindow(string c, string t);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out R r);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
  public struct R { public int L, T, Rt, B; } }
"@
[W]::SetProcessDPIAware() | Out-Null
$h = [W]::FindWindow([NullString]::Value, $Title)
if ($h -eq [IntPtr]::Zero) { $p = Get-Process | Where-Object { $_.MainWindowTitle -like "$Title*" } | Select-Object -First 1; if ($p) { $h = $p.MainWindowHandle } }
if ($h -eq [IntPtr]::Zero) { "no window"; exit 1 }
[W]::SetForegroundWindow($h) | Out-Null
Start-Sleep -Milliseconds 700
$r = New-Object W+R
[W]::GetWindowRect($h, [ref]$r) | Out-Null
$w = $r.Rt - $r.L; $h = $r.B - $r.T
$bmp = New-Object System.Drawing.Bitmap $w, $h
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.CopyFromScreen($r.L, $r.T, 0, 0, $bmp.Size)
$bmp.Save($Out)
"saved $w x $h"
