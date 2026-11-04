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

net session >nul 2>&1
if %errorlevel% neq 0 (
  echo NOT ADMIN. Right-click -^> Run as administrator.
  exit /b 1
)

netsh advfirewall firewall add rule name="carpet-sat-screen-link-8443" dir=in action=allow protocol=TCP localport=8443,8444 enable=yes profile=any

echo.
echo Added. Verify it is really there:
netsh advfirewall firewall show rule name="carpet-sat-screen-link-8443" | findstr /i "规则名 Name 本地端口 LocalPort 启用 Enabled"

echo.
echo To undo later:
echo   netsh advfirewall firewall delete rule name="carpet-sat-screen-link-8443"
echo.
pause
