# Sidecar Launcher — guida rapida

## Installazione

1. Serve **Android 13 o superiore**. Installa `sidecar_launcher-0.2.0.apk` (consenti "installa app sconosciute").
2. Aprilo e concedi calendario, posizione e foto. Al primo avvio (e dopo gli aggiornamenti che aggiungono opzioni) ti chiede come vuoi la barra di stato e la barra gesture.
3. **Accesso alle notifiche** (serve per notifiche e player musicale): *Impostazioni → App → Sidecar Launcher → menu ⋮ → Consenti impostazioni con restrizioni*, poi *Accesso alle notifiche → Sidecar Launcher*.
4. Rendilo la home: *Impostazioni → App predefinite → App Home → Sidecar Launcher*.
5. Le foto arrivano dalla galleria del telefono (la cartella del PC compare solo se il telefono è collegato a un PC con `phonedash`).

## Comandi

### Ovunque sulla home
- **Pizzico con due dita** → impostazioni del launcher
- **Swipe verso il basso** o **tocco sulla barra in alto** → tendina delle notifiche

### Lista app (metà bassa)
- **Tocco** → apre l'app
- **Pressione lunga** → info app
- **Swipe a destra** → fissa / sblocca in cima (i preferiti stanno in un unico riquadro evidenziato)
- **Swipe a sinistra** → nasconde l'app
- **"Cerca app"** in cima → ricerca, anche tra le app nascoste; Invio apre la prima trovata

### Orologio (in basso a sinistra)
- **Pressione lunga** → app Orologio
- **Swipe a destra/sinistra** → pagine a scorrimento continuo (orologio, ore lavorate Billable se configurate)
- **Cerca** → ricerca Google · **icona fotocamera** → Fotocamera

### Widget (metà alta)
- **Tocco sulle tab** o **swipe orizzontale** → Notifiche, Agenda, Casa, Meteo, Foto
- **Doppio tap** ovunque sui widget (anche sopra liste e pulsanti) → mostra / nasconde il player musicale; se non c'è niente in riproduzione compare un player vuoto con "Apri" l'ultima app musicale usata. Senza widget, il doppio tap va fatto nella metà alta (orologio o spazio vuoto)
- **Pressione lunga**:

  | Widget | Apre |
  |---|---|
  | Agenda | Google Calendar |
  | Casa | Google Home |
  | Meteo | meteo Google |
  | Foto | slideshow a schermo intero (tocco per chiudere) |
  | Notifiche | cronologia notifiche |

- **Notifiche**: tocco apre, **X** elimina
- **Casa**: tocco accende/spegne un dispositivo, pressione lunga lo nasconde

### Player
Compare da solo quando c'è musica: precedente / play-pausa / successivo.

**Indietro** chiude impostazioni, ricerca e slideshow.

## Impostazioni (pizzico)

| Categoria | Cosa contiene |
|---|---|
| **Aspetto** | lista app a destra/sinistra (mancini) · colori: di sistema, dal PC (se collegato) o palette (8 predefinite o colore personalizzato) · sfondo nero, scuro tenue o immagine (con trasparenza regolabile di riquadri e pillole, come in DMS) · barra di stato personalizzata (batteria + notifiche a pallini o a carosello, colori del tema o bianco su nero, pillola opzionale) o di sistema · barra gesture visibile o nascosta · larghezza della lista app · dimensione icone · nomi delle app o solo icone (una colonna centrata) · icone a tema · lingua: sistema / IT / EN |
| **Animazioni** | player: Off / 30 s / Sempre · zoom lento delle foto |
| **Widget e orologio** | ordine e visibilità delle tab (senza tab l'orologio va in alto, a sinistra / al centro / a destra) · pagine sotto l'orologio |
| **App** | ordine dei preferiti · app nascoste da ripristinare |
| **Calendari** | quali calendari mostrare in agenda |
| **Google Home, meteo, Billable** | collega/scollega Google Home · città del meteo (vuoto = posizione) · Billable Hours (solo con l'app Pebble installata): URL e parola segreta, con lo stato dell'ultimo aggiornamento |
| **Foto** | origine (PC o galleria) · album · durata di ogni foto |
| **Sistema** | accesso notifiche · launcher predefinito · sfondo di sistema nero (solo home) · impostazioni Android |

## Note

- **Google Home** usa un'API non ufficiale: si collega con il proprio account dalle impostazioni e potrebbe smettere di funzionare in futuro.
- **Billable Hours** serve solo a chi usa quella watchapp Pebble: le sue opzioni compaiono solo se c'è l'app Pebble.
- Le opzioni che dipendono da un'altra scelta restano visibili ma in grigio, con una nota che spiega quando si attivano.
- Con **"Sfondo di sistema nero"** i colori Material You del telefono diventano grigi: meglio scegliere un colore di base in *Sfondo e stile*.
- Per prestazioni migliori dopo l'installazione via adb:
  `adb shell cmd package compile -m speed-profile -f dev.vq.sidecarlauncher`
