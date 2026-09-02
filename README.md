# RealDualWield — port per Minecraft 26.2 (Paper / Purpur)

Port di [RealDualWield](https://github.com/JavaPlugins/RealDualWield) (GPLv3, autore originale LoneDev)
aggiornato per **Minecraft 26.2**, cioè per i server **Paper / Purpur / Pufferfish / Leaf 26.2**
(Java 25, protocollo 776). Mantiene la stessa API pubblica (`beer.devs.realdualwield.api.*`) e lo
stesso comportamento del plugin originale, animazione della spada nella seconda mano compresa.

> ✅ **Minecraft 26.2 esiste davvero**: è uscito il 16 giugno 2026 ("Chaos Cubed"), richiede Java 25
> e usa il nuovo schema di versioni `anno.drop` (26.1, 26.2, 26.3...). Non esiste 1.22.
> Il tuo `purpur-26.2.build.2620` è una build regolare.

---

## L'errore che avevi (e perché succede)

```
java.lang.IllegalArgumentException: Cannot create instance of class
net.minecraft.network.protocol.game.ClientboundAnimatePacket
        at com.comphenix.protocol.injector.StructureCache.newInstance(StructureCache.java:95)
        at beer.devs.realdualwield.Wielder.offhandAnimation(Wielder.java:108)
```

Il plugin originale faceva:

```java
PacketContainer pack = ProtocolLibrary.getProtocolManager().createPacket(PacketType.Play.Server.ANIMATION, false);
pack.getEntityModifier(player.getWorld()).write(0, player);
pack.getIntegers().write(1, 3);
```

Su Minecraft 26.x `ClientboundAnimatePacket` è diventato **immutabile** (record con campi `final`,
senza costruttore vuoto né costruttore da `FriendlyByteBuf`). ProtocolLib prova a istanziare il
pacchetto in vari modi (`StructureCache#determineBestCreator`); quando non ci riesce lancia
`IllegalArgumentException`. Di conseguenza l'animazione dell'arma nella mano secondaria non partiva
più e veniva loggato l'errore a ogni interazione.

## La soluzione

Nuova classe [`OffhandAnimation`](src/main/java/beer/devs/realdualwield/OffhandAnimation.java):
la strategia con cui produrre l'animazione viene **risolta a runtime, una volta sola**, e scritta
nel log al primo utilizzo.

| Strategia | Come funziona | Quando viene usata |
|---|---|---|
| **PACKET** (ProtocolLib) | Il pacchetto NMS viene costruito **da noi via reflection** (costruttore `(int entityId, int action)`, oppure `(Entity, int)`, oppure senza argomenti + campi `int`) e poi **passato già pronto a ProtocolLib** con `new PacketContainer(PacketType.Play.Server.ANIMATION, handle)`. Nessuna richiesta di istanziazione a ProtocolLib → l'errore sparisce. | Versioni moderne (26.x) in cui il pacchetto è un record |
| **PACKET** (classico) | `createPacket(...)` + scrittura dei campi, esattamente come faceva il plugin originale | Versioni in cui ProtocolLib sa ancora allocare il pacchetto da solo |
| **API** | `LivingEntity#swingOffHand()` (Paper/Purpur) → esegue la stessa `swing(InteractionHand.OFF_HAND, true)` vanilla, quindi lo stesso pacchetto, ma senza toccare NMS | Fallback universale (o se lo chiedi da config) |

Se una strategia fallisce si passa automaticamente alla successiva: **l'animazione non si rompe più**,
qualsiasi versione di ProtocolLib (o nessuna) tu abbia.

All'avvio, al primo swing, vedrai una riga come:

```
[RealDualWield] Off-hand swing animation: ProtocolLib packet (record constructor: entityId + action)
```
oppure
```
[RealDualWield] Off-hand swing animation: LivingEntity#swingOffHand() (no packet)
```

---

## Installazione

1. Scarica `releases/RealDualWield-1.3.0.jar` (o compilesela, vedi sotto).
2. Mettilo in `plugins/`.
3. Riavvia. **ProtocolLib è ora opzionale**: se c'è lo usa, altrimenti usa l'API di Paper.
   - Se lo tieni, serve una build che supporti 26.2 (le release 5.4.0 **non** bastano: prendi
     l'ultimo dev build da ci.dmulloy2.net / GitHub Actions di dmulloy2, minimo 5.5.0-SNAPSHOT).
4. (Facoltativo) In `config.yml`:
   ```yaml
   offhand-animation-method: auto   # auto | packet | api
   ```
   - `auto` (default): pacchetto se possibile, altrimenti API
   - `packet`: forza l'invio del pacchetto (raggio 32 blocchi, comportamento identico all'originale)
   - `api`: solo `swingOffHand()`, niente pacchetti (utile se un altro plugin litiga col pacchetto)

---

## Altre modifiche per renderlo compatibile con 26.2

Oltre al fix dell'animazione, il codice è stato allineato alle API moderne:

- **`api-version: 26.2`** in `plugin.yml` (era `1.13`).
- **Adventure al posto di `net.md_5.bungee.api.ChatColor`**: barra di cooldown e messaggi ora usano
  `Component` / `NamedTextColor` e `showTitle(Title…)` / `clearTitle()`.
- **`Damageable` (ItemMeta) al posto di `ItemStack#getDurability()`/`setDurability(short)`** e di
  `Material#getMaxDurability()`, API deprecate da anni: la durabilità dell'arma nella seconda mano
  ora usa `hasMaxDamage()/getDamage()/setDamage()`.
- **`EntityDamageByEntityEvent`**: sostituito il costruttore deprecato basato su `DamageModifier`
  con quello semplice (stesso risultato, danno 0, serve solo per chiedere agli altri plugin se
  l'attacco è consentito).
- **ProtocolLib da `depend` a `softdepend`**: il plugin ora funziona anche senza.
- **Nexo al posto di ItemsAdder**: il controllo "l'oggetto nella mano principale è un blocco
  custom" ora usa `com.nexomc.nexo.api.NexoItems` / `NexoBlocks`, agganciati via reflection
  (`NexoHook`): nessuna dipendenza in compilazione, nessun errore se l'API cambia o se Nexo non
  c'è. In `config.yml` l'opzione `nexo-check` decide se considerare solo i blocchi Nexo
  (`block`, default), tutti gli item Nexo (`item`) o niente (`false`).
- **Task del cooldown spostato sul main thread** (mandava title in async).
- Piccoli controlli null-safety in più (attributi armor mancanti, blocco nullo, item nullo).
- `plugin.yml` + `config.yml` documentati, aggiunte le permission `rdw.use` / `rdw.reload`.

Il comportamento di gioco (danni, knockback, particelle, suoni, instamine delle piante, barra di
cooldown) è rimasto identico alla 1.2.0.

### Robustezza

Tutte le chiamate ad Adventure (componenti, `showTitle`) sono protette da un try/catch con
**fallback sulle API legacy** (`sendMessage(String)`, `sendTitle(String, String, int, int, int)`):
se un server dovesse avere una versione di Adventure diversa da quella contro cui è stato
compilato il plugin, il plugin parte comunque e la barra di cooldown continua a funzionare. Lo
stesso vale per l'animazione: se il pacchetto non arriva a nessuno, si passa da solo a
`swingOffHand()`.

### Animazione proporzionata alla velocità dell'arma

Dal 26.1 la durata (e la forma: **whack** / **stab**) dell'animazione di swing è il data component
`minecraft:swing_animation` **dell'oggetto che si sta muovendo**: se l'item non ce l'ha, il client
usa un fisso di **6 tick**. Le armi MMOItems sono costruite su un materiale vanilla e questo
componente non ce l'hanno, quindi swingano tutte allo stesso modo.

Con `offhand-swing-animation: auto` il plugin lo scrive sulle armi che ancora non lo definiscono:

```
durata = 10 / attack-speed   →   6 tick per una spada da 1,6 atk/s (il default vanilla)
                                 fino a 20 tick (1s) per un grande spadone lento
tipo   = STAB se il materiale base è una spada, WHACK altrimenti
```

Viene scritto **solo sugli item che non ce l'hanno**, quindi una scelta esplicita (di MMOItems o di
un altro plugin) non viene mai sovrascritta, e tocca solo l'animazione: nessuna texture, stat o NBT
viene modificata. `offhand-swing-animation: 12` forza 12 tick, `none` non tocca mai l'item.

### Animazione di ricarica della mano secondaria ("la spada che si abbassa")

È l'animazione che si vede dopo un colpo: **l'arma si abbassa e resta giù finché l'attacco non è di
nuovo carico**, e la sua durata è la velocità di attacco dell'arma (un'arma lenta resta giù molto
più a lungo). Va tenuta distinta dallo swing, che è il movimento del braccio del colpo.

**In vanilla la fa solo la mano principale, per scelta del client.** Nel sorgente 26.2 di
`ItemInHandRenderer` l'altezza dell'arma in mano è calcolata così:

```java
float mainHandTargetHeight = mainHandItem != nextMainHand ? 0.0F : attackAnim * attackAnim * attackAnim;
float offHandTargetHeight  = offHandItem  != nextOffHand  ? 0.0F : 1.0F;
//                                                                 ^^^^^ la seconda mano è SEMPRE alzata
```

`attackAnim` è `Player#getItemSwapScale()`, cioè il tempo di ricarica, azzerato dal client quando
attacca (o spacca un blocco, o cambia l'item nella mano principale). Per la seconda mano il client
non ha proprio uno stato di ricarica: nessun pacchetto e nessuna API del server può chiederlo. Le
uniche due cose che la abbassano sono il cambio dell'item (animazione di swap) e l'uso dell'oggetto,
e in entrambi i casi torna su in ~3 tick, qualsiasi sia l'arma.

Il plugin aggira il limite con `offhand-recharge`:

| valore      | effetto                                                                                                                                                                          |
|-------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `hold`      | **(default)** l'arma **si abbassa davvero** e resta giù per **tutta la ricarica**, poi risale: la durata è il tempo di ricarica dell'arma (`20 / attack-speed`), quindi una spada lenta resta giù molto più a lungo, come la mano principale in vanilla. Costa un pacchetto a tick. Ha due limiti, entrambi strutturali: (1) serve che un pacchetto arrivi dentro *ogni singolo tick* del client, e quando il server ha un singhiozzo il client la risolleva di 0,4 e quello dopo la ributta giù (l'effetto "su e giù", più visibile con le armi lente); (2) il profilo non è identico a quello vanilla, perché la mano principale risale con una curva cubica (comincia a salire dalla metà della ricarica) mentre la seconda mano può solo stare giù o su, e quindi resta giù e risale negli ultimi 3 tick. |
| `dip`       | l'arma si abbassa a ogni colpo di seconda mano: ~3 tick giù e ~3 tick su, **un solo pacchetto a colpo**, e il movimento lo fa tutto il client da sé, quindi **non può glitchare**. La durata è però sempre la stessa (~6 tick): **non** segue la velocità di attacco. |
| `cooldown`  | la classica barra di ricarica vanilla: l'arma nello slot della seconda mano mostra la barra bianca per **esattamente** il tempo di ricarica dell'arma, senza il minimo glitch e con un solo pacchetto a colpo. È la barra dell'HUD, non l'arma che si muove in 3D: scrive sull'item il componente `minecraft:use_cooldown` (con un gruppo di cooldown suo) e, siccome questo cambia l'item, il client ci aggiunge da sé un breve sussulto. |
| `none`      | nessun feedback di ricarica.                                                                                                                                                     |

Con `dip` e `hold` **l'item vero non viene mai modificato**: le copie vivono solo dentro i
pacchetti, quindi stat MMOItems, texture Nexo/MMOItems, durabilità e custom model data restano
intatti. Se un item model del resource pack disattiva l'animazione di swap
(`hand_animation_on_swap: false`) `dip` e `hold` non hanno nulla da animare: usa `cooldown`.

Lo scatto avviene anche quando colpisci **l'aria**, perché in vanilla anche un colpo a vuoto fa
partire la ricarica.

### Colpire subito dopo la mano principale

In vanilla un mob colpito è **invulnerabile per 10 tick (0,5s)** e in quella finestra vengono
scartati tutti i danni non *superiori* al precedente. Il dual wielding sarebbe quindi inutile:
il colpo di seconda mano subito dopo quello di prima mano verrebbe ignorato. Con
`offhand-ignore-no-damage-ticks: true` (default) l'invulnerabilità viene azzerata prima di
applicare il danno della seconda mano, che così entra sempre per intero. Mettilo a `false` se
vuoi la regola vanilla (la seconda mano aggiunge solo la differenza).

Siccome l'invulnerabilità viene azzerata, premere **click sinistro + click destro insieme**
sarebbe però un doppio danno istantaneo: ci pensa `offhand-delay-after-main-hand` (in tick).

| Valore | Effetto |
|---|---|
| `8` (default) | 0,4s: la combo 1-2 è possibile, ma servono due click distinti, non un solo frame |
| `10` | 0,5s: la finestra di invulnerabilità vanilla (entra solo la differenza di danno) |
| `0` | nessun ritardo: sconsigliato, è un cheat enorme di DPS |

Il ritardo vale **in entrambe le direzioni**: la seconda mano aspetta dopo un colpo di prima mano
e la prima mano aspetta dopo un colpo di seconda mano, altrimenti azzerare l'invulnerabilità
trasformerebbe *seconda → prima* in un doppio danno gratuito. Mentre il ritardo è in corso non
parte nulla: né danno, né animazione, né consumo di mana.

Di conseguenza il colpo di seconda mano **non lascia il bersaglio invulnerabile**: così la combo
`principale → seconda → principale` entra tutta e tre le volte (prima l'ultimo colpo veniva
ingoiate dai 10 tick di invulnerabilità vanilla generati dal nostro stesso colpo).

### MMOItems / MMOCore / Nexo

Il plugin si integra con MMOItems e MMOCore **solo via reflection**: nessuna dipendenza in
compilazione, niente si rompe se mancano o se cambiano API. Tutto è riassunto nella sezione
`mmoitems:` di `config.yml` e ogni controllo è visibile con `debug: true`.

| Cosa | Come |
|---|---|
| **Armi TWO-HANDED** | `MMOHook.isEncumbered()` richiama esattamente lo stesso controllo che fa MMOItems prima di usare un'arma (`PlayerData#isEncumbered()`, con fallback identico sul NBT `TWO_HANDED` / `HANDWORN`). Un'arma two-handed **nella seconda mano non attacca mai** (niente danno, niente knockback, niente animazione, niente mana); un'arma two-handed **nella prima mano disabilita entrambe le mani** (`cancel-main-hand-attack` annulla anche gli attacchi della principale). I catalizzatori (stat `Handworn`) non contano come "secondo oggetto". |
| **Mana / Stamina** | Il `MANA_COST` / `STAMINA_COST` dell'arma nella seconda mano viene letto con `NBTItem#getStat` e scalato dalle risorse del giocatore con `PlayerData#giveMana` / `giveStamina` (MMOCore), come fa `Weapon#applyWeaponCosts`. Se il giocatore non ne ha abbastanza l'attacco non parte e (opzionale) riceve il messaggio configurato. |
| **Delay d'attacco** | Il cooldown della seconda mano usa l'`attack-speed` MMOItems (`20 / attacchi al secondo`, cioè **lo stesso identico tempo di ricarica che l'arma ha nella mano principale**, limitato da `min-cooldown` / `max-cooldown`, default 4–200 tick = 0,2–10s) invece dei 12 tick fissi; il danno viene scalato sulla durata reale del cooldown e, con `enforce-cooldown`, mentre è in corso l'attacco è saltato. La barra di cooldown si riempie **su tutta la durata** del cooldown, non più solo nei primi 8 tick. |
| **Danno e attributi** | Il danno base resta quello dell'attributo `ATTACK_DAMAGE` dell'item (è quello che MMOItems scrive per la stat `attack-damage`), quindi l'arma MMOItems nella seconda mano colpisce con il suo danno. Il resto (critici, danni elementali, PvE/PvP%, lifesteal, effetti on-hit) lo aggiunge MythicLib/MMOItems sul nostro `EntityDamageByEntityEvent`, esattamente come per un attacco normale. |
| **Tutte le armi MMOItems** | `all-mmoitems-weapons: true` (default): se l'item è un'arma MMOItems (`Type#isWeapon()`) è utilizzabile in seconda mano anche se il suo materiale vanilla (carta, bastone, osso...) non è in `dual_wield_enabled.materials`. |
| **Texture** | Il plugin **non modifica mai l'item**: non tocca NBT, né `CustomModelData`, né i componenti. In più, di default **non applica la durabilità vanilla** agli item MMOItems (`apply-durability: false`), così gli item con *texture by durability* o con la durabilità custom di MMOItems restano intatti. |
| **Nexo** | Vedi `nexo-check`. |

#### Nota su MythicLib (il mana della mano principale non viene toccato)

MythicLib (la libreria condivisa da MMOItems/MMOCore) trasforma ogni danno in un
`PlayerAttackEvent`, e in `DamageManager#findAttack` lo costruisce **sempre** con
`EquipmentSlot.MAIN_HAND` (suo commento: *"left-hand attacks are handled by specific listeners"*).
MMOItems allora fa:

```java
// net.Indyuce.mmoitems.listener.ItemUse, EventPriority.LOW, ignoreCancelled = true
ItemStack used = player.getInventory().getItem(((MeleeAttackMetadata) event.getAttack()).getHand().toBukkit());
new Weapon(playerData, item).handleTargetedAttack(...);   // prende mana e stamina
```

Cioè: **ogni colpo di seconda mano veniva pagato due volte**, una volta dal plugin (l'arma giusta,
quella della seconda mano) e una volta da MMOItems (quella della prima mano). Con la stessa spada
in entrambe le mani fa esattamente il doppio del mana. C'era di peggio: se l'arma della prima mano
non è un'arma da mischia, o non si hanno i requisiti, o non si ha il mana per **lei**, MMOItems
chiama `setCancelled(true)` e siccome `AttackEvent#setCancelled` scrive direttamente
sull'`EntityDamageEvent` sottostante, la prima mano poteva **annullare il danno della seconda**.

Il plugin ora nasconde il colpo di seconda mano a MMOItems, e solo a MMOItems: annulla il
`PlayerAttackEvent` a `LOWEST` (prima del suo listener, che è `LOW` con `ignoreCancelled = true`) e
lo rimette come era a `NORMAL`, così MythicLib (che lo lancia dal suo listener `HIGHEST` sul danno)
ritrova l'evento intatto: tipi di danno, modificatori e `PlayerKillEntityEvent` continuano a
funzionare come prima. L'unica arma che paga il colpo è quella che ha colpito: la seconda mano.

### `/rdwdebug` (o `/rdwreload debug`)

**Nota:** `/rdwdebug` è un comando nuovo: se il server non è stato **riavviato** dopo l'aggiornamento
(non basta `/reload` e non basta un plugin manager) il comando non esiste ancora. In quel caso usa
**`/rdwreload debug`**, che dà lo stesso identico report ed è registrato dalla prima versione del
plugin.

Non avendo a disposizione i jar di MMOItems/MMOCore (sono plugin premium), l'integrazione è
**solo via reflection e con più nomi candidati per ogni metodo** (`NBTItem.get` *oppure* il wrapper
di MythicLib, `getStat` *oppure* `getDouble`, `isEncumbered` *oppure* `areHandsFull`,
`giveMana(double)` *oppure* `giveMana(double, UpdateReason)`...). Se uno non viene trovato si
passa al successivo e solo quella feature resta muta.

Per sapere **esattamente** cosa è stato risolto sul tuo server:

```
/rdwdebug
```

stampa (e scrive in console) un report tipo:

```
[RealDualWield] version 1.6.0 on Minecraft 26.2
[RealDualWield] off-hand animation: ProtocolLib packet (record constructor: entityId + action)
[RealDualWield] ProtocolLib: found
[RealDualWield] Nexo: hooked
[RealDualWield] MMOItems: v6.10.1
[RealDualWield] MMOCore: v1.13.1
[RealDualWield]   [OK]      NBTItem.get(ItemStack)  (io.lumine.mythic.lib.api.item.NBTItem)
[RealDualWield]   [MISSING] NBTItem#getStat(String)  (io.lumine.mythic.lib.api.item.NBTItem)
[RealDualWield]   ...
[RealDualWield] features: two handed = true, mana/stamina = false, attack speed = false, ...
```

Incollami quelle righe e adatto `MMOHook` ai nomi reali della tua build.

### Debug

Se l'attacco con la seconda mano non fa danno, metti `debug: true` in `config.yml` e fai
`/rdwreload`. Ogni click destro scrive in console una riga `[RealDualWield][debug]` con arma,
bersaglio, danno calcolato e salute prima/dopo; se l'attacco viene saltato la riga dice
esattamente **quale** controllo lo ha bloccato (long press, materiale non abilitato, un altro
plugin che ha annullato il danno, ...). Ricordati di rimettere `debug: false`: è verboso.

---

## Compilare da sorgente

Serve **JDK 25+**:

```bash
./gradlew build          # jar in build/libs/RealDualWield-1.3.0.jar
```

Le dipendenze vengono prese da:
- `io.papermc.paper:paper-api:26.2-R0.1-SNAPSHOT` (repo.papermc.io)
- `com.comphenix.protocol:ProtocolLib:5.4.0` (repo.dmulloy2.net) — serve solo in compilazione

Entrambe le versioni sono in `gradle.properties`. Se `repo.dmulloy2.net` non è raggiungibile puoi
usare jitpack:

```groovy
compileOnly 'com.github.dmulloy2.ProtocolLib:ProtocolLib:master-SNAPSHOT'
```

C'è anche la GitHub Action `.github/workflows/build.yml`: se abiliti le Actions sul repository,
ogni push compila il plugin e carica il jar come artifact.

Il jar già pronto in `releases/` è stato compilato con ECJ (Eclipse Compiler for Java) contro i
**sorgenti reali** di Paper 26.2 e di ProtocolLib master: la compilazione è pulita, 0 errori.

---

## Licenza

GPLv3, come l'originale (vedi `LICENSE`).
