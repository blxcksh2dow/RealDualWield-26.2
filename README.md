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
- **ItemsAdder** agganciato via reflection (`ItemsAdderHook`): non serve più il repository Maven
  `maven.devs.beer` per compilare e non si rompe se l'API cambia.
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
