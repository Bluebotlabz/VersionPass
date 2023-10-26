# VersionPass

### Introduction

Versionpass allows `1.20.30` clients to join a `1.20.40` server

Note: `VersionPass` requires 2 NBT files downloaded and placed in the `data` directory from [here](https://github.com/GeyserMC/Geyser/tree/master/core/src/main/resources/bedrock)

(download the `block_palette.1_20_30.nbt` and `block_palette.1_20_40.nbt` files)

__ProxyPass requires  Java 17 or later<br>
If using VersionPass in offline mode (default), `online-mode` __needs to be set to__ `false` __in__ `server.properties` __so that ProxyPass can communicate with your Bedrock Dedicated Server.__
Credentials in `online-mode` can be saved by setting `save-auth-details` to `true`.

### Building & Running
To produce a jar file, run `./gradlew shadowJar` in the project root directory. This will produce a jar file in the `build/libs` directory.

If you wish to run the project from source, run `./gradlew run` in the project root directory.

### Compatability
- [x] Placing blocks
- [x] Breaking blocks
- [x] Interacting with blocks
- [ ] Interacting with entities
- [x] PVE
- [ ] PVP
- [x] Movement
- [x] Joining world
- [ ] Viewing world (NEEDS SUBCHUNK v0 SUPPORT, OTHERWISE WORKS)
- [ ] Support for older versions
- [ ] Block RID unhashing (needed to support older versions)

### Links

__[Jenkins](https://ci.opencollab.dev/job/NukkitX/job/ProxyPass/job/master/)__

__[Protocol library](https://github.com/CloudburstMC/Protocol) used in this project__

### Credits
- Online mode auth code taken from [ViaBedrock](https://github.com/RaphiMC/ViaBedrock/tree/main)
- Subchunk parser based on code from [Chunky](https://github.com/WaterdogPE/Chunky)
