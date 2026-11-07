param(
    [Parameter(Mandatory = $true)][string]$TextFile,
    [Parameter(Mandatory = $true)][string]$WavOut,
    [string]$Voice = "Microsoft Huihui Desktop - zh-CN",
    [int]$Rate = 1
)
# Offline Chinese TTS on this box: System.Speech (ships with Windows) -> 16k mono s16le WAV.
# Comments must stay ASCII: Windows PowerShell 5.1 decodes a BOM-less UTF-8 .ps1 as GBK and
# can eat the line after a Chinese comment. The Chinese text itself comes from $TextFile (UTF-8).
$ErrorActionPreference = 'Stop'

# Two voices behind one entry point, and the order is the decision, not an accident.
# 1) Edge neural (zh-CN-XiaoyiNeural): a young female voice, free, no key, no install of a
#    language pack. It needs network at the moment of speaking, which is exactly why 2) stays.
# 2) System.Speech (Huihui): ships with Windows, works with the Wi-Fi off. Sounds like a
#    sat-nav prompt, so it is the fallback, not the demo voice.
# Python is probed by explicit path only. A bare `python` on this box is the broken Windows
# Store alias (exits 49 without running anything), so it must not appear in the candidate list.
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$edge = Join-Path $here 'tts-edge.py'
# The @() wrapper is not decoration: with one match Where-Object returns a bare string, and
# $cands[0] on a string is its FIRST CHARACTER, so PowerShell tries to run "D" as a command.
$cands = @(@($env:CARPET_TTS_PY, 'D:\dev\python311\python.exe') | Where-Object { $_ -and (Test-Path $_) })
if ((Test-Path $edge) -and $cands.Count -gt 0) {
    $line = & $cands[0] $edge --text-file $TextFile --wav-out $WavOut 2>&1 | Select-Object -Last 1
    if ($LASTEXITCODE -eq 0 -and "$line" -like 'OK *') { Write-Output "$line"; exit 0 }
    # Edge missed (offline, quota, decode): say so in the same one line the operator reads,
    # then speak with the offline voice. Silent swap = "why does it sound like a robot today".
    Write-Output ("EDGE-SKIP " + ("$line" -replace '[^\x20-\x7E]', '?'))
}

Add-Type -AssemblyName System.Speech
$s = New-Object System.Speech.Synthesis.SpeechSynthesizer
try {
    $s.SelectVoice($Voice) | Out-Null
} catch {
    # The shipped name is localized (on this box it is "Microsoft Huihui Desktop" without the
    # " - zh-CN" suffix), so a miss is not an error. Fall back to any enabled zh voice and let
    # the OK line report which one actually spoke; only a total miss is worth exiting on.
    $zh = $s.GetInstalledVoices() | Where-Object { $_.VoiceInfo.Culture.Name -like 'zh*' -and $_.Enabled } | Select-Object -First 1
    if ($null -eq $zh) { Write-Output "NO-ZH-VOICE"; exit 2 }
    $s.SelectVoice($zh.VoiceInfo.Name) | Out-Null
}
$text = [System.IO.File]::ReadAllText($TextFile, [System.Text.Encoding]::UTF8).Trim()
if ($text.Length -eq 0) { Write-Output "EMPTY-TEXT"; exit 3 }
$fmt = New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(
    16000,
    [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen,
    [System.Speech.AudioFormat.AudioChannel]::Mono)
$s.Rate = $Rate
$s.SetOutputToWaveFile($WavOut, $fmt)
$s.Speak($text)
$s.SetOutputToNull()
$len = (Get-Item $WavOut).Length
Write-Output ("OK " + $len + " bytes voice=" + $s.Voice.Name + " chars=" + $text.Length)
