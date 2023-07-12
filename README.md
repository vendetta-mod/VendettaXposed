# Table of Contents
1. [Installation](#installation)
2. [Non-root](#non-root)
3. [Root](#root)
4. [Info](#vendettaxposed)
5. [Credits](#credits)

# Installation

1. download [vendetta.gabe616.apk](https://github.com/Gabe616/VendettaMod-VendettaXposed/releases/download/latest/vendetta.gabe616.apk) from the latest release

##### Non-Root

2. in **Vendetta Manager**'s settings under **Developer only**, set the **Module location** to
   ```
   /storage/emulated/0/Download/vendetta.gabe616.apk
   ```
   - developer mode should be enabled by going to the Home page > Info icon (upper right corner) > tapping on version
3. reinstall **Vendetta**

##### Root

2. reinstall by **deleting existing module**[^1]

> **Note**
> If you want to revert to the original XPosed module, just press "Reset module location"

[^1]:Signatures changed, so you'll have to delete original module if you've used Xposed module before.

# VendettaXposed

An Xposed module to inject Vendetta, a mod for Discord's mobile apps.

### Credits

I do not wish to write Kotlin, nor do I know much of it, so much of this repo can be attributed to the [first commit of AliucordXposed](https://github.com/Aliucord/AliucordXposed/commit/79ad1e224d598643057cd057c83fab851e89ac82).
