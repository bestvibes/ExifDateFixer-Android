# ExifDateFixer
An Android app that runs [exiftool](https://exiftool.org/) using a cross-compiled version of Perl for Android. This can be used to run exiftool in batch on images or directories directly on your phone.

The primary motivation for this was that on my Google Pixel 2 XL, screenshots and images saved from Snapchat don't come with EXIF timestamps embedded in them. The Google Photos app on Android only looks for an EXIF timestamp to determine the time the image was taken, and uses the time of upload as a fallback, ignoring the file creation/modification timestamp.

With this app, I can select all my screenshots and Snapchat images, and embed EXIF timestamps in them based on either their filenames (Android screenshots store the timestamp in the filename) or file modification date (used for Snapchat images). This way Google Photos can pick up the time the images were actually taken and my memories can be preserved. There is also an option to use custom flags for exiftool in case the default options I've provided don't suffice.

## Usage

[A debug APK](ExifDateFixer-debug.apk) is provided for convenience. Feel free to install it and give it a shot. All this app requires is permissions to access your files.

You can also check this repo out and build it yourself if you'd like, no special bells or whistles are required.

## Implementation

The implementation was inspired by [ru.al.exiftool](https://apkpure.com/exiftool/ru.al.exiftool). The project packages exiftool and cross-compiled versions of Perl for arm and x86 (PIE only since I don't care about supporting below Android 5.0). It will determine the Perl version required, and extract that along with exiftool to its files directory under /data. It will set appropriate permissions for the executables and run exiftool for you at your command!

I've tried to make the UI as flexible as possible so I will never need to come back to this code to add new functionality by adding support for custom commands. The only reason I hope to need to come back is to add more frequently-used commands as easy one-click options.
