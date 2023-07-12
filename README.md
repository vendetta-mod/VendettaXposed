# Table of Contents
1. [Installation](https://github.com/riichimaru/xposed-silly-patch/README.md/#Installation)
2. [Non-root](https://github.com/riichimaru/xposed-silly-patch/README.md/##Non-root)
3. [Root](https://github.com/riichimaru/xposed-silly-patch/README.md/##Root)
4. [Info](https://github.com/riichimaru/xposed-silly-patch/README.md/#VendettaXposed)
5. [Credits](https://github.com/riichimaru/xposed-silly-patch/README.md/#Credits)

# Installation

1. download [vendetta.gabe616.apk](https://github.com/Gabe616/VendettaMod-VendettaXposed/releases/download/8/vendetta.gabe616.apk) from the latest release

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

[^1]:Signatures changed, so you'll have to delete original module if you've used Xposed module directly.

# VendettaXposed

An Xposed module to inject Vendetta, a mod for Discord's mobile apps.

### Credits

I do not wish to write Kotlin, nor do I know much of it, so much of this repo can be attributed to the [first commit of AliucordXposed](https://github.com/Aliucord/AliucordXposed/commit/79ad1e224d598643057cd057c83fab851e89ac82).
