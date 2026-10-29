firmware/t505n/
Plaintext source files for the SM-T505N one-tap EDL (9008) buttons ("Format + FRP"
and "Erase FRP" on the T505N tab). Mirrors firmware/x216b/ -- these are NOT
committed yet; the app will show "FIRMWARE CHECK" / "MISSING" on the T505N tab
until all four files below are added here and the APK is rebuilt.
Required files (exact names, case-sensitive):
File
Purpose
T505N.elf
Firehose programmer/loader for the SM-T505N
frp.bin
FRP partition image
persistent.bin
persist partition image
misc.bin
misc partition image
