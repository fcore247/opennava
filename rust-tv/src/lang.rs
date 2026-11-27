//! The TV screen is written in German. For English viewers its texts are translated as they are drawn.
//! The paired phone also chooses the country, which decides whether the German broadcasters appear.

use std::borrow::Cow;
use std::sync::atomic::{AtomicBool, Ordering};

static ENGLISH: AtomicBool = AtomicBool::new(false);
static AMERICAN: AtomicBool = AtomicBool::new(false);

/// The content of locale.json, such as {"language":"en","country":"us"}. Returns true when it changed.
pub fn apply(json: &str) -> bool {
    let value: serde_json::Value = serde_json::from_str(json).unwrap_or_default();
    let english = value["language"] == "en";
    let american = value["country"] == "us";
    let changed = ENGLISH.swap(english, Ordering::Relaxed) != english;
    (AMERICAN.swap(american, Ordering::Relaxed) != american) || changed
}

pub fn american() -> bool {
    AMERICAN.load(Ordering::Relaxed)
}

pub fn english() -> bool {
    ENGLISH.load(Ordering::Relaxed)
}

/// The text to draw: unchanged for German, translated for English. Unknown texts, such as the
/// titles of programmes, stay as they are.
pub fn tr(text: &str) -> Cow<'_, str> {
    if !english() {
        return Cow::Borrowed(text);
    }
    translate(text)
}

fn translate(text: &str) -> Cow<'_, str> {
    if let Some((_, english)) = TEXTS.iter().find(|(german, _)| *german == text) {
        return Cow::Borrowed(english);
    }
    for (before, after, english_before, english_after) in PATTERNS {
        if text.len() >= before.len() + after.len()
            && let Some(rest) = text.strip_prefix(before)
            && let Some(value) = rest.strip_suffix(after)
        {
            return Cow::Owned(format!("{english_before}{value}{english_after}"));
        }
    }
    Cow::Borrowed(text)
}

/// Texts with one changing part: German start and end, English start and end.
const PATTERNS: [(&str, &str, &str, &str); 3] = [
    ("", " Fotos wechseln sich ab", "", " photos take turns"),
    ("Der Anbieter antwortet mit HTTP ", ".", "The provider answers with HTTP ", "."),
    ("YouTube · ", "", "YouTube · ", ""),
];

const TEXTS: &[(&str, &str)] = &[
    // Home and navigation
    ("Was möchtest du ansehen?", "What would you like to watch?"),
    ("OK  ·  Suchen", "OK  ·  Search"),
    ("Mediatheken  ·  YouTube", "Libraries  ·  YouTube"),
    ("YouTube  ·  Apps", "YouTube  ·  Apps"),
    ("Live-TV", "Live TV"),
    ("Mediatheken", "Libraries"),
    ("Mediathek", "Library"),
    ("Sender auswählen", "Choose a library"),
    ("Deine Bibliotheken", "Your libraries"),
    ("Aktuelle Sendungen", "Latest programmes"),
    ("Deine Mediathek", "Your library"),
    ("YouTube durchsuchen", "Search YouTube"),
    ("TV-Apps", "TV apps"),
    ("Eine installierte App öffnen", "Open an installed app"),
    ("Bereits auf deinem Fernseher", "Already on your TV"),
    ("Suche", "Search"),
    ("Mediatheken und YouTube", "Libraries and YouTube"),
    ("Merkliste", "Watchlist"),
    ("Auf diesem Gerät gespeichert", "Saved on this device"),
    ("Einstellungen", "Settings"),
    ("Vorschau verlassen", "Leave preview"),
    ("Öffne OpenNava oder kehre zu deinen Apps zurück.", "Open OpenNava or return to your apps."),
    ("Weitere TV-Apps", "More TV apps"),
    ("Live-TV Apps", "Live TV apps"),
    ("‹ Zurück", "‹ Back"),
    ("Weitere ›", "More ›"),
    ("Weiter ›", "Next ›"),
    ("Videos suchen", "Search videos"),
    ("YouTube-Konto", "YouTube account"),
    ("Auf YouTube suchen …", "Search YouTube …"),
    ("Titel eingeben …", "Type a title …"),
    ("Leerzeichen", "Space"),
    ("Löschen", "Delete"),
    ("Leeren", "Clear"),
    ("Alle", "All"),
    ("Start", "Home"),
    ("Wohin möchtest du?", "Where would you like to go?"),
    ("Weiter ausprobieren", "Keep exploring"),
    ("Zur klassischen Ansicht", "Classic view"),
    ("Vorschau schließen", "Close preview"),
    ("Aktuelle Folgen suchen", "Find new episodes"),
    ("Aus Merkliste entfernen", "Remove from watchlist"),
    ("Für später vormerken", "Save for later"),
    ("Gespeicherter Titel aus der ersten Vorschau.", "A title saved in the first preview."),
    // Folders
    ("Ordner", "Folder"),
    ("Meine Apps", "My apps"),
    ("Dieser Ordner ist leer.", "This folder is empty."),
    ("Ordner schließen", "Close folder"),
    // Lists of videos
    ("OK zum Abspielen · OK halten zum Entfernen aus der Merkliste", "OK to play · hold OK to remove from watchlist"),
    ("OK zum Abspielen · OK halten zum Vormerken", "OK to play · hold OK to save"),
    ("Weitere Videos werden geladen …", "Loading more videos …"),
    ("Die Suche konnte keine Ergebnisse laden.", "The search could not load any results."),
    ("Weitere Treffer konnten nicht geladen werden. Mit → erneut versuchen.", "More results failed to load. Press → to retry."),
    ("Keine weiteren abspielbaren Treffer gefunden.", "No more playable results."),
    ("Mit → weitere Videos laden.", "Press → for more videos."),
    ("Wiedergabe wird vorbereitet …", "Preparing playback …"),
    ("Videos werden geladen …", "Loading videos …"),
    ("Sendungen werden geladen …", "Loading programmes …"),
    ("Keine passenden Titel gefunden.", "No matching titles."),
    ("Erneut laden", "Reload"),
    ("Abspielen", "Play"),
    ("Hier erscheinen deine vorgemerkten Sendungen.", "Programmes you save appear here."),
    ("Sendungen entdecken", "Discover programmes"),
    // Settings
    ("Darstellung", "Appearance"),
    ("Startseite und Aussehen", "Home screen and look"),
    ("Hintergrundbilder", "Background images"),
    ("Deine eigenen Fotos", "Your own photos"),
    ("Handy & Konten", "Phone & accounts"),
    ("Verbinden und anmelden", "Connect and sign in"),
    ("System", "System"),
    ("Updates und Beenden", "Updates and exit"),
    ("Personalisierung", "Personalisation"),
    ("Apps und Ordner auf der Startseite", "Apps and folders on the home screen"),
    ("Abdunkeln: stark", "Dimming: strong"),
    ("Der Hintergrund ist sehr dunkel", "The background is very dark"),
    ("Abdunkeln: leicht", "Dimming: light"),
    ("Der Hintergrund bleibt gut sichtbar", "The background stays clearly visible"),
    ("Startoberfläche wählen", "Choose home screen"),
    ("Beim Drücken der Home-Taste", "When you press Home"),
    ("Handy verbinden", "Connect phone"),
    ("Fernbedienung, Mediatheken und Fotos vom Handy", "Remote, libraries and photos from your phone"),
    ("YouTube-Konto verwalten", "Manage YouTube account"),
    ("An- und abmelden", "Sign in and out"),
    ("Einrichtung erneut starten", "Run setup again"),
    ("App laden, verbinden und kennenlernen", "Get the app, connect and take the tour"),
    ("Update verfügbar", "Update available"),
    ("Nach Updates suchen", "Check for updates"),
    ("OpenNava auf diesem Fernseher", "OpenNava on this TV"),
    ("Home-Taste: OpenNava", "Home button: OpenNava"),
    ("Home-Taste: Amazon", "Home button: Amazon"),
    ("Welche Startseite die Taste öffnet", "Which home screen the button opens"),
    ("Die bisherigen Apps und Daten bleiben erhalten", "Your apps and data stay as they are"),
    ("Zurück zu deinem Fernseher", "Back to your TV"),
    ("OpenNava zurücksetzen", "Reset OpenNava"),
    ("Alles löschen und neu einrichten", "Delete everything and set up again"),
    ("Noch keine eigenen Fotos", "No photos of your own yet"),
    ("1.  Öffne OpenNava Companion auf deinem Handy.", "1.  Open OpenNava Companion on your phone."),
    ("2.  Einstellungen  ›  Background images  ›  Add photos.", "2.  Settings  ›  Background images  ›  Add photos."),
    ("3.  Wähle ein Foto oder mehrere – sie erscheinen hier.", "3.  Choose one or more photos – they appear here."),
    ("Standardbild aktiv", "Default picture in use"),
    ("1 Foto als Hintergrund", "1 photo as background"),
    ("Standardbild", "Default picture"),
    ("Markiere mit OK mehrere Fotos, um sie automatisch zu wechseln.", "Press OK on several photos to rotate them."),
    ("Foto wechseln", "Change photo"),
    ("Alle 30 Sekunden", "Every 30 seconds"),
    ("Jede Stunde", "Every hour"),
    ("Jeden Tag", "Every day"),
    // Problems
    ("Die Änderung konnte nicht gespeichert werden.", "The change could not be saved."),
    ("YouTube ist gerade nicht erreichbar.", "YouTube is not reachable right now."),
    ("Der Katalog ist gerade nicht erreichbar.", "The catalogue is not reachable right now."),
    ("Die klassische OpenNava-App ist nicht verfügbar.", "The classic OpenNava app is not available."),
    ("Diese App ist auf diesem Fernseher nicht verfügbar.", "This app is not available on this TV."),
    ("Android konnte dieses Ziel nicht öffnen.", "Android could not open this."),
    ("Die Mediatheken antworten nicht.", "The libraries are not answering."),
    ("Der Anbieter ist gerade nicht erreichbar.", "The provider is not reachable right now."),
    ("Die Antwort des Anbieters konnte nicht gelesen werden.", "The provider’s answer could not be read."),
    ("Die Mediathek ist kein gültiges JSON.", "This library is not valid JSON."),
    ("Unbekanntes Mediathek-Format.", "Unknown library format."),
    ("Das ist weder eine OpenNava-Mediathek noch ein Video-Feed.", "This is neither an OpenNava library nor a video feed."),
    ("Diese Mediathek enthält keine abspielbaren Videos.", "This library has no playable videos."),
    ("Ungültige Adresse der Mediathek.", "Invalid library address."),
    ("YouTube hat ungültige Daten geliefert.", "YouTube sent invalid data."),
    ("YouTube konnte die Videos nicht laden.", "YouTube could not load the videos."),
];

#[cfg(test)]
mod tests {
    use super::translate;

    #[test]
    fn translates_known_texts_and_leaves_the_rest() {
        assert_eq!(translate("Einstellungen"), "Settings");
        assert_eq!(translate("3 Fotos wechseln sich ab"), "3 photos take turns");
        assert_eq!(translate("Tagesschau"), "Tagesschau");
    }
}
