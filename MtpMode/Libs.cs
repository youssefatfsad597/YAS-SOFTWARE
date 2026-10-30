using System;
using System.Diagnostics;
using System.IO;
using System.Threading.Tasks;

namespace MA_UNLOCKER.utils;

internal class Libs
{
	public static Process ActiveProcess { get; set; }

	public static void KillActiveProcess()
	{
		try
		{
			if (ActiveProcess != null && !ActiveProcess.HasExited)
			{
				ActiveProcess.Kill(entireProcessTree: true);
			}
		}
		catch
		{
		}
		finally
		{
			ActiveProcess = null;
		}
	}

	public static string GetProjectRoot()
	{
		string text = AppDomain.CurrentDomain.BaseDirectory;
		while (!string.IsNullOrEmpty(text))
		{
			if (Directory.Exists(Path.Combine(text, "sdks")))
			{
				return text;
			}
			text = Path.GetDirectoryName(text);
		}
		return AppDomain.CurrentDomain.BaseDirectory;
	}

	public static Task RunCommandAsync(string exeName, string command, Action<string> logCallback)
	{
		TaskCompletionSource<object> tcs = new TaskCompletionSource<object>();
		string projectRoot = GetProjectRoot();
		Process process = new Process();
		process.StartInfo.WorkingDirectory = projectRoot;
		process.StartInfo.FileName = Path.Combine(projectRoot, "sdks", exeName);
		process.StartInfo.Arguments = command;
		process.StartInfo.UseShellExecute = false;
		process.StartInfo.RedirectStandardOutput = true;
		process.StartInfo.RedirectStandardError = true;
		process.StartInfo.CreateNoWindow = true;
		process.StartInfo.WindowStyle = ProcessWindowStyle.Hidden;
		process.OutputDataReceived += delegate(object s, DataReceivedEventArgs ev)
		{
			if (ev.Data != null)
			{
				logCallback?.Invoke(ev.Data);
			}
		};
		process.ErrorDataReceived += delegate(object s, DataReceivedEventArgs ev)
		{
			if (ev.Data != null)
			{
				logCallback?.Invoke("ERROR: " + ev.Data);
			}
		};
		process.EnableRaisingEvents = true;
		process.Exited += delegate
		{
			if (ActiveProcess == process)
			{
				ActiveProcess = null;
			}
			logCallback?.Invoke("Process finished.");
			tcs.SetResult(null);
		};
		process.Start();
		ActiveProcess = process;
		process.BeginOutputReadLine();
		process.BeginErrorReadLine();
		return tcs.Task;
	}

	public static void CreateFolder(string name)
	{
		try
		{
			if (!Directory.Exists(name))
			{
				Directory.CreateDirectory(name);
			}
		}
		catch
		{
		}
	}

	public static string GetBytesReadable(long i)
	{
		long num = ((i < 0) ? (-i) : i);
		string text;
		double num2;
		if (num >= 1152921504606846976L)
		{
			text = "EB";
			num2 = i >> 50;
		}
		else if (num >= 1125899906842624L)
		{
			text = "PB";
			num2 = i >> 40;
		}
		else if (num >= 1099511627776L)
		{
			text = "TB";
			num2 = i >> 30;
		}
		else if (num >= 1073741824)
		{
			text = "GB";
			num2 = i >> 20;
		}
		else if (num >= 1048576)
		{
			text = "MB";
			num2 = i >> 10;
		}
		else
		{
			if (num < 1024)
			{
				return i.ToString("0 B");
			}
			text = "KB";
			num2 = i;
		}
		return (num2 / 1024.0).ToString("0.### ") + text;
	}
}
