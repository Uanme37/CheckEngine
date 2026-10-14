param([string]$Title = "Check Engine")
# Exit 0 if a window with exactly this title is open (also dialogs that aren't a process's main window).
Add-Type @"
using System; using System.Runtime.InteropServices;
public class H { [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern IntPtr FindWindow(string c, string t); }
"@
if ([H]::FindWindow([NullString]::Value, $Title) -ne [IntPtr]::Zero) { exit 0 } else { exit 1 }
