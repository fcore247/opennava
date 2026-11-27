package site.navatv.companion;

import java.util.HashMap;
import java.util.Map;

/** The app is written in English; for German speakers its texts are looked up here. */
final class L {
    static volatile boolean german;
    private static final Map<String, String> DE = new HashMap<>();

    static String t(String english) {
        if (!german || english == null) return english;
        String found = DE.get(english);
        if (found != null) return found;
        if (english.startsWith("Signed in as ")) return "Angemeldet als " + english.substring(13);
        if (english.matches("\\d+ photos?")) return english.replace("photos", "Fotos").replace("photo", "Foto");
        return english;
    }

    private static void put(String... pairs) {
        for (int i = 0; i < pairs.length; i += 2) DE.put(pairs[i], pairs[i + 1]);
    }

    static {
        // Tabs, header and notices
        put("Home", "Start", "Library", "Mediathek", "Remote", "Fernbedienung", "Settings", "Einstellungen",
                "Back", "Zurück", "Close", "Schließen", "Cancel", "Abbrechen", "Connect", "Verbinden", "Done", "Fertig",
                "My libraries", "Meine Mediatheken", "My streams", "Meine Streams", "Background images", "Hintergrundbilder",
                "YouTube account", "YouTube-Konto", "Connection", "Verbindung", "Recordings", "Aufnahmen", "TV apps", "TV-Apps",
                "TV home", "TV-Startseite", "Looking for your TV…", "Suche deinen Fernseher …",
                "No TV connected", "Kein Fernseher verbunden", "Connecting…", "Verbinde …",
                "Your TV is asleep", "Dein Fernseher schläft", "Wake", "Wecken",
                "Your TV is waiting for text", "Dein Fernseher wartet auf Text", "Type", "Tippen",
                "Choose the TV you want to control.", "Wähle den Fernseher, den du steuern möchtest.",
                "Send text", "Text senden", "Send", "Senden", "Text sent.", "Text gesendet.");
        // Start page
        put("Good morning", "Guten Morgen", "Good afternoon", "Hallo", "Good evening", "Guten Abend",
                "What would you like to watch?", "Was möchtest du sehen?",
                "Hold and drag to arrange. Tap − to remove.", "Halten und ziehen zum Sortieren. − zum Entfernen.",
                "Libraries", "Mediatheken", "Your videos", "Deine Videos", "Open on your TV", "Am Fernseher öffnen",
                "Not installed on this TV", "Nicht auf diesem Fernseher", "LIVE TV", "LIVE-TV",
                "LIVE ON YOUR TV", "LIVE AUF DEINEM TV", "All channels and the TV guide", "Alle Sender und das Programm",
                "Apps on your TV", "Apps auf deinem TV", "See all  ›", "Alle anzeigen  ›",
                "Connect your TV to see its apps", "Verbinde deinen TV, um seine Apps zu sehen",
                "Asking your TV for its apps…", "Frage deinen TV nach seinen Apps …",
                "No apps reported yet", "Noch keine Apps gemeldet",
                "Your start page is empty.\nTap the pencil to add widgets.", "Deine Startseite ist leer.\nTippe auf den Stift, um Widgets hinzuzufügen.",
                "Add widget", "Widget hinzufügen", "Add widgets", "Widgets hinzufügen",
                "Reset to the original layout", "Ursprüngliche Anordnung", "What is on right now", "Was gerade läuft",
                "A row of your TV’s apps", "Eine Reihe deiner TV-Apps", "App on your TV", "App auf deinem TV",
                "Connect your TV to add its apps.", "Verbinde deinen TV, um seine Apps hinzuzufügen.",
                "Look again", "Erneut suchen", "OPENNAVA", "OPENNAVA", "Live TV", "Live-TV");
        // Settings
        put("Your TV", "Dein Fernseher", "Account", "Konto", "Appearance", "Aussehen", "Content", "Inhalte", "App", "App",
                "YOUR TV", "DEIN FERNSEHER", "ACCOUNT", "KONTO", "APPEARANCE", "AUSSEHEN", "CONTENT", "INHALTE", "APP", "APP",
                "Connect a TV", "Fernseher verbinden", "Open any app installed on your TV", "Jede App auf deinem TV öffnen",
                "Standby / Wake", "Standby / Wecken", "Switch the TV on or off", "Fernseher ein- oder ausschalten",
                "Add your own media libraries", "Eigene Mediatheken hinzufügen",
                "Add your own live channels", "Eigene Live-Sender hinzufügen",
                "Recordings and reminders", "Aufnahmen und Erinnerungen",
                "Software updates", "Software-Updates", "Keep the phone and TV current", "Handy und TV aktuell halten",
                "Run setup again", "Einrichtung erneut starten", "Connect a TV and take the tour", "TV verbinden und Führung ansehen",
                "Personalization", "Personalisierung", "Signed in", "Angemeldet", "Not signed in", "Nicht angemeldet",
                "Language and country", "Sprache und Land", "LANGUAGE", "SPRACHE", "COUNTRY", "LAND");
        // Mini player and remote
        put("Playing on TV", "Läuft am TV", "Live on your TV", "Live auf deinem TV",
                "OpenNava on your TV", "OpenNava auf deinem TV", "Open live TV  ›", "Live-TV öffnen  ›",
                "Open NavaTube  ›", "NavaTube öffnen  ›", "Paused  ·  ", "Pausiert  ·  ",
                "Navigation", "Navigation", "Menu", "Menü", "Playback", "Wiedergabe", "Volume down", "Leiser",
                "Volume up", "Lauter", "Mute / Unmute", "Ton an / aus");
        // Setup
        put("Welcome to", "Willkommen bei",
                "Your phone and your TV, finally together.\nSetting up takes about a minute.",
                "Dein Handy und dein TV, endlich zusammen.\nDie Einrichtung dauert etwa eine Minute.",
                "Get started", "Los geht’s", "I’ll do this later", "Später",
                "Where are you?", "Wo bist du?",
                "Your country decides which TV channels and libraries OpenNava shows.",
                "Dein Land bestimmt, welche Sender und Mediatheken OpenNava zeigt.",
                "Continue", "Weiter", "Germany", "Deutschland", "Austria", "Österreich", "Switzerland", "Schweiz",
                "United States", "USA",
                "Scan your TV", "Scanne deinen TV",
                "On your TV, press “Next” (“Weiter”) until you see the second code (step 2 of 2). Then scan it with this app.",
                "Drücke am Fernseher auf „Weiter“, bis der zweite Code erscheint (Schritt 2 von 2). Scanne ihn dann mit dieser App.",
                "Scan TV code", "TV-Code scannen", "Use a photo of the code", "Foto des Codes verwenden",
                "That was the download code. On your TV, press “Next” and scan the second code.",
                "Das war der Download-Code. Drücke am TV auf „Weiter“ und scanne den zweiten Code.",
                "That is not an OpenNava TV code. Scan the code from step 2 on your TV.",
                "Das ist kein OpenNava-TV-Code. Scanne den Code aus Schritt 2 auf deinem TV.",
                "If your TV asks to allow debugging, choose “Allow” and tick “Always”.",
                "Fragt dein TV, ob Debugging erlaubt ist, wähle „Zulassen“ und „Immer“.",
                "One last step", "Ein letzter Schritt",
                "Google TV asks for a code the first time. On your TV open:\nDeveloper options  ›  Wireless debugging  ›\nPair device with pairing code",
                "Google TV verlangt beim ersten Mal einen Code. Öffne am TV:\nEntwickleroptionen  ›  Debugging über WLAN  ›\nGerät über einen Kopplungscode koppeln",
                "Pair and connect", "Koppeln und verbinden", "My TV asked me to “Allow” instead", "Mein TV fragt nach „Zulassen“",
                "Looking for the code screen on your TV…", "Suche die Code-Anzeige auf deinem TV …",
                "✓  Code screen found on your TV", "✓  Code-Anzeige auf deinem TV gefunden",
                "Not found yet. You can also type the port from the TV.", "Noch nicht gefunden. Du kannst den Port auch eintippen.",
                "Not quite yet", "Noch nicht ganz",
                "Check that phone and TV use the same Wi-Fi. If your TV shows “Allow debugging?”, choose “Allow”, then try again.",
                "Prüfe, ob Handy und TV im selben WLAN sind. Zeigt der TV „Debugging zulassen?“, wähle „Zulassen“ und versuche es erneut.",
                "Try again", "Erneut versuchen", "Enter a pairing code instead", "Stattdessen Code eingeben",
                "Scan again", "Neu scannen", "Connected", "Verbunden", "Show me around", "Zeig mir alles",
                "Skip the tour", "Führung überspringen");
        // Tour
        put("See what is on right now. Tap to open all channels, then send one to your TV.",
                "Sieh, was gerade läuft. Tippe für alle Sender und schick einen an deinen TV.",
                "Libraries and NavaTube", "Mediatheken und NavaTube",
                "Browse ARD, ZDF and ARTE or NavaTube here and press play. It starts on the big screen.",
                "Stöbere hier in ARD, ZDF, ARTE oder NavaTube. Ein Tipp, und es läuft auf dem großen Bildschirm.",
                "Browse your libraries or NavaTube here and press play. It starts on the big screen.",
                "Stöbere hier in deinen Mediatheken oder NavaTube. Ein Tipp, und es läuft auf dem großen Bildschirm.",
                "Make it yours", "Mach sie zu deiner",
                "Tap the pencil to move tiles around, remove them, or add any app from your TV.",
                "Mit dem Stift verschiebst und entfernst du Kacheln oder fügst jede App deines TVs hinzu.",
                "All channels", "Alle Sender", "Spin through your channels and the TV guide.", "Drehe durch deine Sender und das Programm.",
                "All libraries", "Alle Mediatheken", "Search every library and your own ones.", "Durchsuche alle Mediatheken, auch deine eigenen.",
                "Your remote", "Deine Fernbedienung",
                "Arrows, volume and typing. Home takes your TV back to its start page.",
                "Pfeile, Lautstärke und Tippen. „Start“ bringt deinen TV zurück zur Startseite.",
                "The mini player", "Der Mini-Player",
                "While something plays, a small player floats above the tabs. Tap it to open it, or close it with the cross.",
                "Läuft etwas, schwebt ein kleiner Player über der Leiste. Tippe zum Öffnen oder schließe ihn mit dem Kreuz.",
                "Ask your AI agent to set up your library", "Lass deinen KI-Agenten deine Mediathek einrichten",
                "For example: “Add this video feed to OpenNava.” Your agent needs a computer that is allowed to reach your TV.",
                "Zum Beispiel: „Füge diesen Video-Feed zu OpenNava hinzu.“ Dein Agent braucht einen Computer, der auf deinen TV zugreifen darf.",
                "Skip tour", "Überspringen", "Next", "Weiter");
        // Library, live and app pages
        put("＋ Add", "＋ Neu", "Check your connection and try again.", "Prüfe deine Verbindung und versuche es erneut.",
                "Nothing found", "Nichts gefunden", "Try another search.", "Versuche eine andere Suche.",
                "Show this search on the TV", "Diese Suche am TV zeigen", "Play on TV", "Am TV abspielen",
                "Show more", "Mehr anzeigen", "Recommended", "Empfohlen", "Results", "Ergebnisse", "More to watch", "Mehr ansehen",
                "German", "Deutsch", "Regional", "Regional", "Mine", "Meine", "Your own stream", "Dein Stream",
                "Live stream", "Livestream", "Earlier", "Früher", "Pause", "Pause", "Resume", "Fortsetzen",
                "Save", "Speichern", "Record", "Aufnehmen", "Today", "Heute", "Yesterday", "Gestern", "Now", "Jetzt",
                "Watch on TV", "Am TV ansehen", "No programme guide", "Kein Programm",
                "Search apps", "Apps suchen", "Refresh list", "Liste neu laden");
    }

    private L() {}
}
