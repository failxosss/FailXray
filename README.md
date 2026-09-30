# XRay

Admin plugin for **Paper 1.21+**. The `/xray` command shows an admin all ores around them (default radius 200 blocks) and refreshes the view every 2 seconds.

## Features
- Ores are visible through walls as glowing colored cubes (each ore type has its own color)
- Only the admin who enabled X-Ray can see the cubes
- Automatic refresh every 2 s (mined ores disappear)
- Asynchronous chunk scanning with a cache, minimal load on the main thread

## Installation
1. Download `XRay-*.jar` from [Releases](../../releases) (or from the artifact in the Actions tab)
2. Put it in your `plugins/` folder
3. Restart the server

## Usage
| Command | Description | Permission |
|---------|-------------|------------|
| `/xray` | Toggles X-Ray on/off | `xray.use` (default: OP) |

## Configuration (`plugins/XRay/config.yml`)
```yaml
radius: 200               # radius in blocks
update-interval-ticks: 40 # 40 ticks = 2 s
max-entities: 2000        # max ores shown at once (nearest first)
chunks-per-tick: 4        # chunk scanning speed
```

## Notes
- The plugin only sees loaded chunks, so the effective radius is limited by the server's `view-distance` (at least 13 for 200 blocks).
- If you only see the cubes up close, increase `entity-tracking-range` in `spigot.yml`.
- Requires Paper (or a fork such as Purpur). It does not work on plain Spigot.

## Building
You need Java 21 and Maven:
```bash
mvn package
```
Output: `target/XRay-1.0.0.jar`

## Releasing a new version
```bash
git tag v1.0.0
git push origin v1.0.0
```
GitHub Actions builds the jar automatically and attaches it to the Release.

## License
MIT
