# XRay

Admin plugin pro **Paper 1.21+**. Příkazem `/xray` se adminovi zobrazí všechny rudy v okolí (výchozí dosah 200 bloků) a zobrazení se aktualizuje každé 2 sekundy.

## Funkce
- Rudy jsou vidět skrz zdi jako barevně svítící kostky (každá ruda má svou barvu)
- Kostky vidí jen admin, který má X-Ray zapnuté
- Automatická aktualizace každé 2 s (vytěžená ruda zmizí)
- Asynchronní skenování chunků s cache, minimální zátěž hlavního vlákna

## Instalace
1. Stáhni `XRay-*.jar` z [Releases](../../releases) (nebo z artefaktu v záložce Actions)
2. Vlož do složky `plugins/`
3. Restartuj server

## Použití
| Příkaz | Popis | Oprávnění |
|--------|-------|-----------|
| `/xray` | Zapne / vypne X-Ray | `xray.use` (výchozí: OP) |

## Konfigurace (`plugins/XRay/config.yml`)
```yaml
radius: 200               # dosah v blocích
update-interval-ticks: 40 # 40 ticků = 2 s
max-entities: 2000        # max. zobrazených rud naráz (nejbližší mají přednost)
chunks-per-tick: 4        # rychlost skenování chunků
```

## Poznámky
- Plugin vidí jen načtené chunky, dosah tedy omezuje `view-distance` serveru (pro 200 bloků alespoň 13).
- Pokud kostky vidíš jen zblízka, zvyš `entity-tracking-range` v `spigot.yml`.
- Vyžaduje Paper (nebo fork, např. Purpur), na čistém Spigotu nefunguje.

## Sestavení
Potřebuješ Java 21 a Maven:
```bash
mvn package
```
Výsledek: `target/XRay-1.0.0.jar`

## Vydání nové verze
```bash
git tag v1.0.0
git push origin v1.0.0
```
GitHub Actions jar automaticky sestaví a přiloží k Release.

## Licence
MIT
