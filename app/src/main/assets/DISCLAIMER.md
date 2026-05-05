# Disclaimer of Warranty and Liability

ExifToolWrapper is provided **"as is", without warranty of any kind**, express or
implied, including but not limited to warranties of merchantability, fitness
for a particular purpose, non-infringement, or that the software is free of
defects, secure, or will produce any particular result.

This app modifies the metadata of your photo and video files, and in some
modes overwrites the original files in place. **You are solely responsible for
maintaining backups** of any files you process with this app. The author and
contributors of ExifToolWrapper accept **no responsibility for data loss, file
corruption, incorrect metadata, broken photo libraries, lost memories, missed
legal deadlines based on EXIF dates, or any other harm** — direct, indirect,
incidental, consequential, or otherwise — arising from your use of this
software.

The app bundles a Perl interpreter (built reproducibly from upstream Perl
source via [perl-cross](https://github.com/arsv/perl-cross)) and the
[ExifTool](https://exiftool.org/) script by Phil Harvey. The exact versions
shipped, together with the SHA256 of every native binary, are pinned in
[`native/PINS`](./native/PINS) and rebuilt by
[`.github/workflows/native.yml`](./.github/workflows/native.yml).

The "advanced custom command" mode lets you pass arbitrary arguments directly
to ExifTool. **You are solely responsible for any commands you run in that
mode**, including any data loss they may cause. The app applies basic
argument-level safety filters (blocking flags such as `-config`, `-@`, and
`-stay_open` that fundamentally widen the trust surface) but does not — and
cannot — guarantee that any particular custom command is safe.

By using this app, you agree to the terms of the [MIT License](./LICENSE) and
acknowledge this disclaimer.
