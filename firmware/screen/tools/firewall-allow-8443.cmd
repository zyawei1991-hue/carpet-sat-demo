@echo off
rem ---------------------------------------------------------------------------
rem  Allow inbound TCP 8443 (screen-link websocket) and 8444 (pixel grab) on THIS
rem  Windows box, so the round screen can talk to the PC instead of a phone.
rem  Right-click this file -> "Run as administrator".  Needs admin exactly ONCE;
rem  the rule survives reboots.  Comments kept ASCII on purpose: cmd.exe reads a
rem  UTF-8 .cmd with the old codepage and can garble the line after Chinese text.
rem
rem  WHY THIS IS NOT THE SAME SITUATION AS THE OTHER DEV MACHINE: that box has the
rem  three firewall profiles pinned by Group Policy (DefaultInboundAction =
rem  BlockInbound, local rule store = N/A), so no local rule can be added there.
rem  This box reports NotConfigured on all three profiles = no policy set it =
rem  the Windows local default (block inbound) stands, but the rule store IS local
rem  (231 inbound allow rules already present).  One elevated run here and the PC
rem  becomes a usable server; on that other box it never will be.
rem ---------------------------------------------------------------------------

rem Each run rewrites D:\dev\tmp\fw-run.log with what it actually did, so a
rem "I ran it and nothing happened" report can be checked against evidence
rem instead of guessed.  (2026-11-05: the LF-only version of this file silently
rem did NOTHING when double-clicked - cmd.exe mis-parses the if-block without CRLF.)
if not exist D:\dev\tmp mkdir D:\dev\tmp
echo RUN %date% %time% > D:\dev\tmp\fw-run.log

net session >nul 2>&1
if %errorlevel% neq 0 (
  echo NOT_ADMIN >> D:\dev\tmp\fw-run.log
  echo NOT ADMIN. Right-click -^> Run as administrator.
  exit /b 1
)

netsh advfirewall firewall add rule name="carpet-sat-screen-link-8443" dir=in action=allow protocol=TCP localport=8443,8444 enable=yes profile=any >> D:\dev\tmp\fw-run.log 2>&1
echo ADD_EXIT=%errorlevel% >> D:\dev\tmp\fw-run.log

echo.
echo Now read the rule back - do not trust the word "Ok":
rem Dump the whole rule instead of grepping it: a Chinese pattern in this file
rem would be decoded with the console codepage and match nothing, which looks
rem exactly like a missing rule.
netsh advfirewall firewall show rule name="carpet-sat-screen-link-8443" >> D:\dev\tmp\fw-run.log 2>&1
netsh advfirewall firewall show rule name="carpet-sat-screen-link-8443"

echo.
echo To undo later:
echo   netsh advfirewall firewall delete rule name="carpet-sat-screen-link-8443"
echo.
pause
