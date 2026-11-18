/* Offline UI language for the full launcher, including dialogs rendered after startup. */
(() => {
  'use strict';
  const languages = 'en de fr es it pt nl da sv no fi pl cs sk hu ro tr uk ja ko'.split(' ');
  const strings = Object.create(null);
  const sourceKeys = new Map();
  function add(key, line) {
    const phrases = line.split('|');
    if (phrases.length !== languages.length) throw new Error('Incomplete TV translation: ' + key);
    strings[key] = Object.fromEntries(languages.map((language, i) => [language, phrases[i]]));
    sourceKeys.set(phrases[0], key);
  }
  // The first value is English. Every following value uses the language order above.
  add('home', 'Home|Start|Accueil|Inicio|Home|Início|Start|Hjem|Hem|Hjem|Aloitus|Start|Domů|Domov|Kezdőlap|Acasă|Ana ekran|Головна|ホーム|홈');
  add('search', 'Search|Suche|Recherche|Buscar|Cerca|Pesquisar|Zoeken|Søg|Sök|Søk|Haku|Szukaj|Hledat|Hľadať|Keresés|Căutare|Ara|Пошук|検索|검색');
  add('apps', 'Apps|Apps|Applications|Aplicaciones|App|Aplicações|Apps|Apps|Appar|Apper|Sovellukset|Aplikacje|Aplikace|Aplikácie|Alkalmazások|Aplicații|Uygulamalar|Застосунки|アプリ|앱');
  add('settings', 'Settings|Einstellungen|Réglages|Ajustes|Impostazioni|Definições|Instellingen|Indstillinger|Inställningar|Innstillinger|Asetukset|Ustawienia|Nastavení|Nastavenia|Beállítások|Setări|Ayarlar|Налаштування|設定|설정');
  add('watchlist', 'Luna watchlist|Luna-Merkliste|Ma liste Luna|Mi lista de Luna|Lista Luna|Lista Luna|Luna-kijklijst|Luna-seerliste|Lunas tittlista|Lunas seerliste|Lunan katselulista|Lista Luna|Seznam Luna|Zoznam Luna|Luna-lista|Lista Luna|Luna izleme listesi|Список Luna|Luna マイリスト|Luna 찜 목록');
  add('live', 'Live TV|Live-TV|TV en direct|TV en directo|TV in diretta|TV em direto|Live-tv|Direkte tv|Direktsänd tv|Direkte-TV|Suora TV|TV na żywo|Živé vysílání|Živé vysielanie|Élő tévé|TV în direct|Canlı TV|Телебачення наживо|ライブTV|실시간 TV');
  add('library', 'Media library|Mediathek|Médiathèque|Videoteca|Videoteca|Videoteca|Mediatheek|Mediebibliotek|Mediebibliotek|Mediebibliotek|Mediakirjasto|Medioteka|Mediatéka|Mediatéka|Médiatár|Bibliotecă media|Medya arşivi|Медіатека|番組ライブラリ|미디어 라이브러리');
  add('quick', 'Quick settings|Quick Settings|Réglages rapides|Ajustes rápidos|Impostazioni rapide|Definições rápidas|Snelle instellingen|Hurtigindstillinger|Snabbinställningar|Hurtiginnstillinger|Pika-asetukset|Szybkie ustawienia|Rychlá nastavení|Rýchle nastavenia|Gyorsbeállítások|Setări rapide|Hızlı ayarlar|Швидкі налаштування|クイック設定|빠른 설정');
  add('pcNotice', 'Manage settings on your computer or use Quick settings.|Einstellungen bitte über den PC regeln oder Quick Settings nutzen.|Réglez votre TV sur votre ordinateur ou utilisez les réglages rapides.|Configura el TV desde el ordenador o usa los ajustes rápidos.|Configura la TV dal computer o usa le impostazioni rapide.|Configure a TV no computador ou use as definições rápidas.|Beheer de instellingen op je computer of gebruik de snelle instellingen.|Brug din computer eller hurtigindstillingerne til at indstille tv’et.|Ändra inställningarna på datorn eller använd snabbinställningarna.|Endre innstillingene på datamaskinen eller bruk hurtiginnstillingene.|Muuta asetuksia tietokoneella tai käytä pika-asetuksia.|Zmień ustawienia na komputerze lub użyj szybkich ustawień.|Nastavte televizi na počítači nebo použijte rychlá nastavení.|Nastavte televízor v počítači alebo použite rýchle nastavenia.|A tévét a számítógépen vagy a gyorsbeállításokban állíthatod be.|Configurează televizorul de pe computer sau folosește setările rapide.|TV’yi bilgisayarınızdan ayarlayın veya hızlı ayarları kullanın.|Налаштуйте телевізор на комп’ютері або скористайтеся швидкими налаштуваннями.|パソコンで設定するか、クイック設定をご利用ください。|컴퓨터에서 설정을 변경하거나 빠른 설정을 이용하세요.');
  add('openLuna', 'Open settings in Luna Browser|Einstellungen im Luna Browser öffnen|Ouvrir les réglages dans Luna Browser|Abrir los ajustes en Luna Browser|Apri le impostazioni in Luna Browser|Abrir definições no Luna Browser|Instellingen openen in Luna Browser|Åbn indstillinger i Luna Browser|Öppna inställningar i Luna Browser|Åpne innstillinger i Luna Browser|Avaa asetukset Luna Browserissa|Otwórz ustawienia w Luna Browser|Otevřít nastavení v Luna Browser|Otvoriť nastavenia v Luna Browser|Beállítások megnyitása a Luna Browserben|Deschide setările în Luna Browser|Ayarları Luna Browser’da aç|Відкрити налаштування в Luna Browser|Luna Browser で設定を開く|Luna Browser에서 설정 열기');
  add('savedCount', '{count} saved|{count} vorgemerkt|{count} dans votre liste|{count} en tu lista|{count} in lista|{count} na lista|{count} op je lijst|{count} på listen|{count} i listan|{count} på listen|Listalla {count}|Na liście: {count}|V seznamu: {count}|V zozname: {count}|Listán: {count}|În listă: {count}|Listede {count}|У списку: {count}|保存済み {count} 件|찜한 항목 {count}개');
  add('empty', 'Nothing saved yet. Mark a programme in Luna Browser and it appears here.|Deine Merkliste ist leer. Merke dir eine Sendung im Luna Browser vor. Sie erscheint dann hier.|Votre liste est vide. Ajoutez une émission dans Luna Browser pour la retrouver ici.|Tu lista está vacía. Guarda un programa en Luna Browser y aparecerá aquí.|La lista è vuota. Salva un programma in Luna Browser per ritrovarlo qui.|A lista está vazia. Guarde um programa no Luna Browser para o ver aqui.|Je lijst is leeg. Bewaar een programma in Luna Browser om het hier te zien.|Listen er tom. Gem en udsendelse i Luna Browser for at se den her.|Din lista är tom. Spara ett program i Luna Browser så visas det här.|Listen er tom. Lagre et program i Luna Browser, så vises det her.|Lista on tyhjä. Tallenna ohjelma Luna Browserissa, niin se näkyy tässä.|Lista jest pusta. Zapisz program w Luna Browser, a pojawi się tutaj.|Seznam je prázdný. Uložte pořad v Luna Browser a objeví se zde.|Zoznam je prázdny. Uložte reláciu v Luna Browser a zobrazí sa tu.|A lista üres. Ments el egy műsort a Luna Browserben, és itt megjelenik.|Lista este goală. Salvează o emisiune în Luna Browser și o vei găsi aici.|Listeniz boş. Luna Browser’da kaydettiğiniz programlar burada görünür.|Список порожній. Збережіть передачу в Luna Browser, і вона з’явиться тут.|リストは空です。Luna Browser で番組を保存すると、ここに表示されます。|아직 저장한 항목이 없습니다. Luna Browser에서 프로그램을 찜하면 여기에 표시됩니다.');
  add('remove', 'Remove|Entfernen|Retirer|Quitar|Rimuovi|Remover|Verwijderen|Fjern|Ta bort|Fjern|Poista|Usuń|Odebrat|Odstrániť|Eltávolítás|Elimină|Kaldır|Вилучити|削除|삭제');
  add('searchPrompt', 'What would you like to watch?|Was möchtest du sehen?|Que voulez-vous regarder ?|¿Qué quieres ver?|Cosa vuoi guardare?|O que quer ver?|Wat wil je kijken?|Hvad vil du se?|Vad vill du se?|Hva vil du se?|Mitä haluaisit katsoa?|Co chcesz obejrzeć?|Na co se chcete podívat?|Čo si chcete pozrieť?|Mit szeretnél nézni?|Ce vrei să urmărești?|Ne izlemek istersiniz?|Що бажаєте подивитися?|何を見たいですか？|무엇을 시청할까요?');
  add('searchShows', 'Search programmes|Sendungen suchen|Rechercher une émission|Buscar programas|Cerca programmi|Pesquisar programas|Programma’s zoeken|Søg efter udsendelser|Sök efter program|Søk etter programmer|Hae ohjelmia|Szukaj programów|Hledat pořady|Hľadať relácie|Műsorok keresése|Caută emisiuni|Program ara|Пошук передач|番組を検索|프로그램 검색');
  add('services', 'Services|Dienste|Services|Servicios|Servizi|Serviços|Diensten|Tjenester|Tjänster|Tjenester|Palvelut|Serwisy|Služby|Služby|Szolgáltatások|Servicii|Hizmetler|Сервіси|サービス|서비스');
  add('all', 'All|Alle|Tout|Todos|Tutti|Todos|Alles|Alle|Alla|Alle|Kaikki|Wszystkie|Vše|Všetky|Összes|Toate|Tümü|Усі|すべて|전체');
  add('noResults', 'No results found.|Keine Treffer gefunden.|Aucun résultat.|No se encontraron resultados.|Nessun risultato.|Sem resultados.|Geen resultaten gevonden.|Ingen resultater.|Inga träffar.|Ingen treff.|Ei hakutuloksia.|Brak wyników.|Žádné výsledky.|Žiadne výsledky.|Nincs találat.|Niciun rezultat.|Sonuç bulunamadı.|Нічого не знайдено.|結果が見つかりません。|검색 결과가 없습니다.');
  add('loading', 'Loading…|Wird geladen …|Chargement…|Cargando…|Caricamento…|A carregar…|Laden…|Indlæser…|Laddar…|Laster …|Ladataan…|Wczytywanie…|Načítání…|Načítava sa…|Betöltés…|Se încarcă…|Yükleniyor…|Завантаження…|読み込み中…|불러오는 중…');
  add('back', 'Back|Zurück|Retour|Volver|Indietro|Voltar|Terug|Tilbage|Tillbaka|Tilbake|Takaisin|Wstecz|Zpět|Späť|Vissza|Înapoi|Geri|Назад|戻る|뒤로');
  add('close', 'Close|Schließen|Fermer|Cerrar|Chiudi|Fechar|Sluiten|Luk|Stäng|Lukk|Sulje|Zamknij|Zavřít|Zavrieť|Bezárás|Închide|Kapat|Закрити|閉じる|닫기');
  add('openRequested', 'Request sent to Luna Browser on your computer.|Anfrage an Luna Browser auf deinem Computer gesendet.|Demande envoyée à Luna Browser sur votre ordinateur.|Solicitud enviada a Luna Browser en tu ordenador.|Richiesta inviata a Luna Browser sul computer.|Pedido enviado para o Luna Browser no computador.|Verzoek naar Luna Browser op je computer verzonden.|Anmodning sendt til Luna Browser på din computer.|Förfrågan skickades till Luna Browser på datorn.|Forespørsel sendt til Luna Browser på datamaskinen.|Pyyntö lähetettiin tietokoneesi Luna Browseriin.|Wysłano prośbę do Luna Browser na komputerze.|Požadavek byl odeslán do Luna Browser v počítači.|Požiadavka bola odoslaná do Luna Browser v počítači.|A kérés elküldve a számítógépen futó Luna Browsernek.|Solicitarea a fost trimisă către Luna Browser de pe computer.|İstek bilgisayarınızdaki Luna Browser’a gönderildi.|Запит надіслано в Luna Browser на комп’ютері.|パソコンの Luna Browser にリクエストを送信しました。|컴퓨터의 Luna Browser에 요청을 보냈습니다.');
  add('pairHint', 'Open Luna Browser on your computer, enter tvsettings and connect this TV.|Öffne Luna Browser auf deinem Computer, gib tvsettings ein und verbinde diesen Fernseher.|Ouvrez Luna Browser sur votre ordinateur, saisissez tvsettings et connectez ce téléviseur.|Abre Luna Browser en tu ordenador, escribe tvsettings y conecta este TV.|Apri Luna Browser sul computer, digita tvsettings e collega la TV.|Abra o Luna Browser no computador, escreva tvsettings e ligue esta TV.|Open Luna Browser op je computer, typ tvsettings en verbind deze tv.|Åbn Luna Browser på computeren, skriv tvsettings og forbind tv’et.|Öppna Luna Browser på datorn, skriv tvsettings och anslut tv:n.|Åpne Luna Browser på datamaskinen, skriv tvsettings og koble til TV-en.|Avaa Luna Browser tietokoneella, kirjoita tvsettings ja yhdistä tämä televisio.|Otwórz Luna Browser na komputerze, wpisz tvsettings i połącz telewizor.|Otevřete v počítači Luna Browser, zadejte tvsettings a připojte televizi.|Otvorte Luna Browser v počítači, zadajte tvsettings a pripojte televízor.|Nyisd meg a Luna Browsert a gépen, írd be, hogy tvsettings, és csatlakoztasd ezt a tévét.|Deschide Luna Browser pe computer, tastează tvsettings și conectează televizorul.|Bilgisayarınızda Luna Browser’ı açın, tvsettings yazın ve bu TV’yi bağlayın.|Відкрийте Luna Browser на комп’ютері, введіть tvsettings і під’єднайте телевізор.|パソコンで Luna Browser を開き、tvsettings と入力してこのテレビを接続してください。|컴퓨터에서 Luna Browser를 열고 tvsettings를 입력한 뒤 이 TV를 연결하세요.');
  add('searchHint', 'Type a title|Titel eingeben|Saisissez un titre|Escribe un título|Inserisci un titolo|Introduza um título|Typ een titel|Skriv en titel|Skriv en titel|Skriv inn en tittel|Kirjoita nimi|Wpisz tytuł|Zadejte název|Zadajte názov|Írj be egy címet|Introdu un titlu|Bir başlık yazın|Введіть назву|タイトルを入力|제목 입력');
  add('searchFailed', 'Search unavailable|Suche derzeit nicht verfügbar|Recherche indisponible|Búsqueda no disponible|Ricerca non disponibile|Pesquisa indisponível|Zoeken tijdelijk niet beschikbaar|Søgning er utilgængelig|Sökningen är inte tillgänglig|Søk er utilgjengelig|Haku ei ole käytettävissä|Wyszukiwanie niedostępne|Hledání není dostupné|Vyhľadávanie nie je dostupné|A keresés nem érhető el|Căutarea nu este disponibilă|Arama kullanılamıyor|Пошук недоступний|検索を利用できません|검색을 이용할 수 없습니다');
  add('searchAgain', 'Try again|Erneut versuchen|Réessayer|Reintentar|Riprova|Tentar novamente|Opnieuw proberen|Prøv igen|Försök igen|Prøv igjen|Yritä uudelleen|Spróbuj ponownie|Zkusit znovu|Skúsiť znova|Újra|Încearcă din nou|Yeniden dene|Спробувати ще раз|再試行|다시 시도');
  add('chooseService', 'Choose a service|Dienst auswählen|Choisir un service|Elige un servicio|Scegli un servizio|Escolha um serviço|Kies een dienst|Vælg en tjeneste|Välj en tjänst|Velg en tjeneste|Valitse palvelu|Wybierz serwis|Vyberte službu|Vyberte službu|Válassz szolgáltatást|Alege un serviciu|Bir hizmet seçin|Виберіть сервіс|サービスを選択|서비스 선택');
  add('unavailable', 'This app is unavailable.|Diese App ist nicht verfügbar.|Cette application est indisponible.|Esta aplicación no está disponible.|Questa app non è disponibile.|Esta aplicação não está disponível.|Deze app is niet beschikbaar.|Denne app er ikke tilgængelig.|Den här appen är inte tillgänglig.|Denne appen er ikke tilgjengelig.|Sovellus ei ole käytettävissä.|Ta aplikacja jest niedostępna.|Tato aplikace není k dispozici.|Táto aplikácia nie je dostupná.|Az alkalmazás nem érhető el.|Aplicația nu este disponibilă.|Bu uygulama kullanılamıyor.|Цей застосунок недоступний.|このアプリは利用できません。|이 앱을 이용할 수 없습니다.');
  strings.searchSubmit = strings.search;
  add('letters', 'Letters|Buchstaben|Lettres|Letras|Lettere|Letras|Letters|Bogstaver|Bokstäver|Bokstaver|Kirjaimet|Litery|Písmena|Písmená|Betűk|Litere|Harfler|Літери|文字|문자');
  add('numbersSymbols', 'Numbers and symbols|Zahlen und Symbole|Chiffres et symboles|Números y símbolos|Numeri e simboli|Números e símbolos|Cijfers en symbolen|Tal og symboler|Siffror och symboler|Tall og symboler|Numerot ja merkit|Cyfry i symbole|Čísla a symboly|Čísla a symboly|Számok és jelek|Numere și simboluri|Sayılar ve simgeler|Цифри й символи|数字と記号|숫자 및 기호');
  const storeKey = 'opennava.full.language.v1';
  const supported = code => {
    const base = String(code || '').trim().toLowerCase().split(/[-_]/)[0];
    if (base === 'nb' || base === 'nn') return 'no';
    return languages.includes(base) ? base : '';
  };
  function deviceLanguage() {
    for (const candidate of [...(navigator.languages || []), navigator.language]) {
      const language = supported(candidate);
      if (language) return language;
    }
    return 'en';
  }
  let preference = 'system';
  try { preference = supported(localStorage.getItem(storeKey)) || 'system'; } catch {}
  let current = supported(preference) || deviceLanguage();
  const interpolate = (value, args = {}) => value.replace(/\{(\w+)\}/g, (match, key) => Object.prototype.hasOwnProperty.call(args, key) ? String(args[key]) : match);
  function translated(key, fallback, args) {
    const entry = strings[key] || strings[sourceKeys.get(fallback)] || window.NavaLegacyStrings?.[fallback];
    return interpolate(entry?.[current] || entry?.en || fallback || key, args);
  }

  // Some older dialogs still build their controls from English labels. Translate only UI,
  // preserving catalogue metadata, installed app names and text entered by the viewer.
  const originalText = new WeakMap();
  const originalAttributes = new WeakMap();
  const skip = 'script,style,svg,canvas,textarea,[contenteditable="true"],[translate="no"],.luna-title,.native-app-identity,.tube-video-title,.tube-channel-name,.tube-video-meta,#tube-query:not(.tube-placeholder),.quick-profile,.luna-programme strong,[data-tv-result] strong';
  const attributes = ['aria-label', 'title', 'placeholder'];
  let templateSource;
  let templates = [];
  function legacyTemplates(legacy) {
    if (legacy === templateSource) return templates;
    templateSource = legacy;
    templates = [];
    for (const [source, row] of Object.entries(legacy)) {
      const parts = source.split(/(\{\w+\})/);
      if (parts.length === 1) continue;
      const keys = [];
      const pattern = parts.map((part, index) => {
        if (index % 2 === 0) return part.replace(/[.*+?^$(){}|[\]\\]/g, '\\$&');
        const name = part.slice(1, -1);
        keys.push(name);
        return name === 'count' ? '([0-9]+)' : '(.+?)';
      }).join('');
      templates.push({pattern:new RegExp('^' + pattern + '$'), keys, row});
    }
    return templates;
  }
  function translateLiteral(value) {
    const key = sourceKeys.get(value);
    if (key) return translated(key, value);
    const legacy = window.NavaLegacyStrings;
    if (!legacy || current === 'en') return value;
    if (legacy[value]) return legacy[value][current] || value;
    for (const {pattern, keys, row} of legacyTemplates(legacy)) {
      const match = pattern.exec(value);
      if (!match || !row[current]) continue;
      const values = Object.fromEntries(keys.map((key, index) => [key, match[index + 1]]));
      return interpolate(row[current], values);
    }
    return value;
  }
  function localizeText(node) {
    if (!node.parentElement || node.parentElement.closest(skip)) return;
    const raw = node.data;
    if (!raw || !raw.trim()) return;
    const previous = originalText.get(node);
    const source = previous?.output === raw ? previous.source : raw;
    const core = source.trim(), local = translateLiteral(core);
    const output = source.replace(core, () => local);
    if (output !== raw) node.data = output;
    if (output !== source || previous) originalText.set(node, {source, output});
  }
  function localizeAttributes(el) {
    if (el.closest(skip)) return;
    let saved = originalAttributes.get(el);
    for (const name of attributes) {
      const raw = el.getAttribute(name);
      if (!raw) continue;
      const previous = saved?.[name];
      const source = previous?.output === raw ? previous.source : raw;
      const output = translateLiteral(source);
      if (output !== raw) el.setAttribute(name, output);
      if (output !== source || previous) {
        if (!saved) { saved = {}; originalAttributes.set(el, saved); }
        saved[name] = {source, output};
      }
    }
  }
  function localize(root) {
    if (root.nodeType === Node.TEXT_NODE) { localizeText(root); return; }
    if (root.nodeType !== Node.ELEMENT_NODE || root.closest(skip)) return;
    localizeAttributes(root);
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_TEXT, {
      acceptNode(node) {
        return node.nodeType === Node.ELEMENT_NODE && node.matches(skip) ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT;
      }
    });
    while (walker.nextNode()) {
      const node = walker.currentNode;
      if (node.nodeType === Node.TEXT_NODE) localizeText(node);
      else if (attributes.some(name => node.hasAttribute(name))) localizeAttributes(node);
    }
  }
  function applyLanguage(next) {
    const changed = next !== current;
    current = next;
    document.documentElement.lang = current;
    if (changed) {
      if (document.body) localize(document.body);
      window.dispatchEvent(new CustomEvent('opennava-language-changed', {detail:current}));
    }
  }
  function setLanguage(code) {
    const next = supported(code);
    preference = next || 'system';
    try { if (next) localStorage.setItem(storeKey, next); else localStorage.removeItem(storeKey); } catch {}
    applyLanguage(next || deviceLanguage());
    return current;
  }
  window.NavaI18n = Object.freeze({
    t: translated, setLanguage, languages: Object.freeze(languages), get language() { return current; }
  });
  applyLanguage(current);
  window.addEventListener('languagechange', () => { if (preference === 'system') applyLanguage(deviceLanguage()); });
  function start() {
    localize(document.body);
    new MutationObserver(changes => {
      for (const change of changes) {
        if (change.type === 'attributes') localizeAttributes(change.target);
        else if (change.type === 'characterData') localizeText(change.target);
        else for (const node of change.addedNodes) localize(node);
      }
    }).observe(document.body, {childList:true, characterData:true, attributes:true, attributeFilter:attributes, subtree:true});
  }
  if (document.body) start();
  else document.addEventListener('DOMContentLoaded', start, {once:true});
})();
