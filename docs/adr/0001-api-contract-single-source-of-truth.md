# 0001 — API-Kontrakt: Single Source of Truth vs. Drift-Guard

- **Status:** Accepted — vom Menschen ratifiziert am 2026-07-17 (Issue [#547](https://github.com/maxliem9/home-base/issues/547))
- **Datum:** 2026-07-17
- **Kontext-Issues:** #547 (dieses ADR) · [#593](https://github.com/maxliem9/home-base/issues/593) (der beschlossene Struktur-Drift-Guard) · #96/#109/#82/#134 (encodeDefaults-Guards) · #452 (Icon-Map-Drift-Guard) · #546 (Service-Schicht) · #552 (Sync-Envelope)

> **Ratifizierung (2026-07-17):** Empfehlung **angenommen** — Full-Codegen vertagt, leichter
> Parität-Guard beschlossen und als #593 (`test-gap`) angelegt. Ausdrücklich bestätigt: **kein**
> dritter Nicht-Kotlin-Client und **keine** öffentliche/Dritt-Consumer-API auf der Roadmap — der
> Haupt-Kipp-Trigger aus §5 ist damit derzeit nicht aktiv.

> **TL;DR:** Codegen aus einer OpenAPI-Spec beseitigt nur die **Typ-Form**-Drift (und die
> betrifft **drei** Quellen, nicht vier — `mockApi.ts` hängt schon heute an `types.ts`).
> Die *teure* Drift — „fehlt = Default"-Leselogik pro Feld, die Tri-State-Semantik der
> Update-DTOs (#265) und die 2 100-Zeilen-Verhaltenskopie in `mockApi.ts` — bleibt **unberührt**.
> Der naheliegendste Weg („Spec aus den Kotlin-Models reflektieren") ist unter
> `encodeDefaults = false` sogar **aktiv gefährlich**: er markiert weggelassene Felder fälschlich
> als `required`. **Empfehlung: Full-Codegen vertagen; stattdessen einen leichten
> Struktur-Drift-Guard** im Stil von #134/#452 einführen.

---

## 1. Problem — vierfach von Hand gepflegter Kontrakt

Dieselben DTO-Formen leben an vier Stellen (Zeilenzahlen Stand 2026-07-17):

| Quelle | Zeilen | Rolle |
|---|---|---|
| `backend/src/main/kotlin/com/homebase/model/Models.kt` | 1 046 | kanonische `@Serializable`-DTOs (Wire-Wahrheit) |
| `android/app/src/main/kotlin/com/homebase/android/data/model/Models.kt` | 1 012 | Moshi-`@JsonClass`-DTOs |
| `web/src/types.ts` | 418 | TS-Interfaces |
| `web/e2e/helpers/mockApi.ts` | 2 142 | handgeschriebene TS-Nachbildung des **ganzen Backends** |

Jede API-Änderung heißt heute: bis zu vier manuelle Edits, jeder mit stillem Drift-Risiko.

### 1a. Wichtige Korrektur der „4-Wege"-Erzählung

`mockApi.ts` ist **keine** vierte, unabhängige Typdefinition. Es **importiert** die Formen aus
`web/src/types.ts` (Kopf der Datei):

```ts
// e2e fixtures use the app's real domain types (src/types.ts) so that any drift —
// e.g. a newly-required field like Note.images — is caught by `npm run typecheck:e2e`
import type { Todo, ShoppingItem, Note, /* … */ } from '../../src/types'
```

Für die **Typ-Form** ist die Drift also **3-Wege** (Backend · Android · Web), und `mockApi`
ist bereits ein *Consumer* der Web-Kandidatenquelle — ein Feld, das in `types.ts` fehlt, ist
in `mockApi` schon heute ein `typecheck:e2e`-Fehler. Die 2 142 Zeilen sind zu ~90 % **Verhalten**
(zustandsbehaftetes CRUD, Tri-State-Merge, Kategorie-Auflösung, Forecast-Mathematik), nicht
Typdeklaration. Das ist zentral für die Bewertung: **Typ-Codegen verkleinert `mockApi.ts` um
praktisch nichts** — dessen Drift-Risiko ist Verhaltens-Parität mit dem Backend, und die
adressiert kein Typgenerator.

### 1b. Wie `encodeDefaults = false` die Drift verschärft

`plugins/Serialization.kt` setzt `encodeDefaults = false`: **jedes** Feld, dessen Wert dem
Kotlin-Default entspricht, wird aus dem JSON **weggelassen** — nicht nur `null` und leere Listen,
sondern auch **Nicht-null-Defaults** wie `= false`, `= 1`, `= "1970-01-01"`. Der Client muss
„fehlt = Default" pro Feld selbst nachbilden.

**Konkretes Beispiel — dasselbe Feld, vier Idiome für „false ⇒ weglassen":**

| Quelle | `ShoppingList(Dto).ownCategories` |
|---|---|
| Backend | `val ownCategories: Boolean = false` → bei `false` weggelassen |
| Android | `val ownCategories: Boolean = false` (Moshi füllt fehlenden Key mit `false`) |
| Web | `ownCategories?: boolean` + Prosa „Omitted by the backend when false" |
| mockApi | `...(ownCategories ? { ownCategories: true } : {})` — **handkodierte Weglass-Logik** |

Dieselbe Regel, vier Mal per Hand — genau die Klasse Fehler, die #96/#109/#82 gebraucht hat.
Weiteres 3-Wege-Beispiel: `Recurrence.interval` (`Int = 1` / `Int = 1` / `interval?: number`
„omitted when 1"). Und `WorkTarget.validFrom` (`= BASE_TARGET_PERIOD`).

**Bereits existierende Inkonsistenz (harmlos, aber echt) — `Note.images` / `attachments`:**
Das Backend kodiert **beide** Listen immer (bewusst kein Default, damit `[]` gesendet wird).
Trotzdem markiert Web `images` als required, `attachments?` als optional; Android markiert
**beide** als `= emptyList()`. Drei Quellen, drei Optionalitäts-Meinungen zu genau denselben
zwei Feldern — belegt, dass selbst sorgfältige Menschen hier auseinanderlaufen.

### 1c. Was Typ-Codegen NICHT anfasst

- **„fehlt = Default"-Leselogik.** Ein generiertes `ownCategories?: boolean` sagt dem Leser
  nicht, dass fehlend `false` bedeutet — `?? false` am Leseort bleibt Handarbeit (s. §3, Crux).
- **Tri-State-Semantik der Update-DTOs (#265):** `null = unverändert`, `"" = löschen`,
  Wert = setzen. Eine reine Prosa-Konvention auf einem `String?` — in **keinem** Typ ausdrückbar.
- **Wert-Maps** wie die Kategorie→Icon-Zuordnung: Genau die Drift, für die #452 einen Guard
  brauchte — und die ein **DTO**-Generator gar nicht sähe (kein DTO-Feld).
- **`mockApi.ts`-Verhalten** (§1a).

---

## 2. Optionen

### Option A — Status quo + gezielte Drift-Guards (wie #134/#452)

Weiter von Hand, aber pro erkannter Drift-Klasse ein mechanischer Quelltext-/Struktur-Guard.

- **+** Null neue Toolchain, keine CI-Generator-Kosten, keine Versionierung; passt exakt zur
  etablierten Projekt-Kultur (#134, #348, #505, #452 sind alle leichte Scanner, keine Generatoren).
- **+** Die load-bearing Prosa-Kommentare (Tri-State, „omitted when …") bleiben am DTO.
- **−** Skaliert nicht automatisch mit; jede *neue* Drift-Klasse braucht einen eigenen Guard.
- **−** Ein reiner „appJson-Instanz"-Guard (#134) prüft Konfiguration, nicht Feld-Parität.

### Option B — OpenAPI-Spec als Quelle der Wahrheit → `types.ts` + Moshi-DTOs generieren

Zwei Unter-Varianten, die sich **fundamental** unterscheiden:

- **B1 — Spec aus den Kotlin-Models reflektiert** (z. B. Ktor-OpenAPI-Plugin): naheliegend,
  aber unter `encodeDefaults = false` **fehlerhaft** — siehe §3. Nicht empfohlen.
- **B2 — Spec von Hand (OpenAPI 3.1) gepflegt**, daraus Clients generiert: korrekt machbar,
  aber die Spec ist dann eine **fünfte** handgepflegte Quelle, bis `backend/Models.kt` abgelöst
  ist — und das Backend müsste seine DTOs *aus* der Spec generieren, um wirklich SSOT zu sein.
- **+** Typ-Form-Drift (3-Wege) wird zum Compile-Fehler.
- **+** Nebenprodukt: maschinenlesbare API-Doku (nützlich, falls je iCal/öffentliche API).
- **−** Löst §1c **nicht** (Leselogik, Tri-State, mockApi-Verhalten, Wert-Maps).
- **−** Laufende Kosten: Generator-Pflege, CI-Job, „Spec-Review" als neuer PR-Schritt,
  Versionierung des Kontrakts.

### Option C — Kotlin `backend/Models.kt` als SSOT, Rest generieren

Wie B1, nur ohne Zwischenformat. Gleiches Kernproblem: der Generator müsste die
kotlinx-Semantik „Nicht-null-Feld **mit** Default ⇒ weggelassen ⇒ `optional`+`default`" kennen.
Tut er das nicht, markiert er falsch `required` (§3). Zusätzlich: Update-DTOs (`null = unchanged`)
und Response-DTOs teilen keine ableitbare Beziehung — der Generator kennt die #265-Konvention nicht.

### Option D — Partiell: nur `web/types.ts` aus dem Backend generieren, Android + mockApi von Hand

Kleinster Codegen-Fußabdruck. TS ist die Stelle, an der eine required/optional-Verwechslung am
billigsten auffällt (Typecheck). Aber: derselbe B1/C-Reflexions-Fallstrick, und der Nutzen ist
gering, solange Android weiter von Hand läuft (die Backend↔Android-Drift bliebe ungeschützt).

---

## 3. Crux — behandelt OpenAPI-Codegen `encodeDefaults = false` sauber?

**Teilweise — und der bequeme Weg ist eine Falle.**

**Was sauber abbildet (Optionalität):** Ein weglassbares Feld → Property **nicht** in der
`required`-Liste, dafür mit `default:`. Gängige TS-Generatoren (`openapi-typescript`,
`openapi-generator typescript-fetch`) emittieren dann `field?: T` — deckt sich mit `types.ts`
heute (`ownCategories?: boolean`). Für Moshi kann `openapi-generator` den Default sogar in den
Data-Class-Konstruktor schreiben (`= false`) — deckt sich mit Android heute. So weit gut.

**Gap 1 — Default-Substitution ist Laufzeit, nicht Typ.** Codegen liefert `ownCategories?: boolean`,
aber **nicht** das `?? false` am Leseort. Ein TS-`interface` hat keinen Laufzeitkörper, der einen
Default einsetzen könnte. Genau die Arbeit, die das Issue beklagt („jeder Client bildet pro Feld
nach, dass es fehlen kann"), ist **Lese-Code** und bleibt zu großen Teilen Handarbeit. Codegen
erzwingt lediglich, dass man `undefined` behandelt — den *Wert* des Defaults setzt es nicht.

**Gap 2 — Backend-first-Reflexion markiert falsch `required` (der gefährliche Teil).** Unter
`encodeDefaults = false` bedeutet ein **Nicht-null**-Feld **mit** Default (`ownCategories: Boolean = false`,
`interval: Int = 1`, `validFrom: String = "1970-01-01"`, jedes `= emptyList()`), dass es am Draht
**fehlt**, wenn es auf dem Default steht. Ein reflektierender Spec-Generator, der „Kotlin-Property
ist non-null" → `required` abbildet (die naive Default-Regel), erzeugt:

```yaml
# FALSCH generiert aus `val ownCategories: Boolean = false` bei encodeDefaults=false
ShoppingList:
  required: [id, name, createdBy, createdAt, ownCategories]   # ← ownCategories gehört hier NICHT hin
  properties:
    ownCategories: { type: boolean }
```

→ generiertes TS: `ownCategories: boolean` (**required**). Das ist falsch: der Server sendet den
Key oft gar nicht → Laufzeit-/Deserialisierungsbruch, und man wäre gezwungen, das Feld überall
mitzusenden. **Korrekt** wäre `required` weglassen **und** `default: false` setzen — die Regel
lautet „Property **hat einen Kotlin-Default** ⇒ *nicht* required, trägt `default:`", **nicht**
„Property ist nullable ⇒ optional". Standard-Ktor-Reflexions-Plugins kennen diese kotlinx-Regel
nicht. Deshalb: **B2 (Spec von Hand) ist sicherer als B1/C (Spec aus Kotlin)** — aber Handpflege
holt die manuelle Quelle zurück, die man loswerden wollte.

> *Methodischer Hinweis:* Ein Live-Generator wurde bewusst **nicht** in den Build verdrahtet
> (Auftrag). Das YAML oben ist illustrativ aus dem dokumentierten Verhalten von
> `openapi-generator`/`openapi-typescript` abgeleitet, nicht aus einem Lauf. Das ändert die
> Schlussfolgerung nicht: der Fehlermodus folgt direkt aus der `required`-Semantik von OpenAPI
> gegen die Weglass-Semantik von kotlinx.

---

## 4. Laufende Kosten vs. tatsächliches Wachstum

- **Wachstumstempo:** 41 Migrationen klingt nach viel Churn, aber die *DTO-Form* ändert sich
  überwiegend **additiv** (neues optionales Feld), selten strukturell. Additive optionale Felder
  sind genau der Fall, den ein billiger Parität-Check abdeckt.
- **Codegen-Dauerkosten:** ein CI-Job + Generator-Version-Pinning + „Spec-Review" als neuer
  PR-Schritt + Kontrakt-Versionierung. Für einen **2-Nutzer-Privat-Hub, Single-Instance, ohne
  öffentliche/Dritt-Consumer** ist das ein schlechtes Verhältnis.
- **Projekt-Kultur:** Jeder bestehende Drift-Schutz hier ist ein **leichter mechanischer Guard**
  (#134 Json-Instanz, #348 Compose-Layout, #505 Flyway-Versionen, #452 Icon-Map), kein Generator.
  Eine Codegen-Toolchain wäre der erste Bruch mit diesem Muster.

---

## 5. Entscheidung / Empfehlung

**Full-Codegen (Optionen B/C/D) vertagen. Stattdessen einen leichten Struktur-Drift-Guard
im Stil von #134/#452 einführen.**

Begründung: Codegen adressiert nur die **3-Wege-Typ-Form-Drift** und lässt die teure Drift
(§1c) liegen; der attraktivste Weg (B1/C) ist unter `encodeDefaults = false` fehlerhaft (§3);
die laufenden Kosten passen nicht zur Größe/Kultur des Projekts (§4).

**Vorgeschlagener Guard (eigenes Issue, nicht Teil dieses ADR):** ein Test (Backend-`src/test`
oder ein Web-Skript), der die DTO-Deklarationen der drei Quellen parst und **pro DTO Feld-Namen
+ Optionalität** vergleicht — fängt genau den „Feld in 2 von 3 Quellen ergänzt"-Fall, ohne
Generator, CI-Toolchain oder Versionierung, und lässt die load-bearing Prosa-Kommentare am DTO.
Kosten: einmalig ein Parser + eine Namens-Mapping-Tabelle (`XxxDto` ↔ `Xxx`), danach quasi
wartungsfrei. Bewusst **kein** Semantik-Check (Tri-State/Default-Werte bleiben Prosa + Reviews).

**Wann diese Entscheidung kippt (Trigger für ein Superseding-ADR):**
- ein **dritter Client** in einer Nicht-Kotlin-Sprache kommt hinzu, **oder**
- eine **öffentliche / Dritt-Consumer-API** entsteht (dann wird eine *hand-authored* OpenAPI-Spec
  ohnehin als Doku gebraucht, und B2 wird attraktiv), **oder**
- der Parität-Guard fängt wiederholt echte Drift (empirischer Beleg, dass Handpflege nicht trägt).

**Grober Migrationspfad, falls doch „adopt" (dann B2, nie B1/C):**
1. OpenAPI **3.1** von Hand für **eine** Domäne (z. B. Shopping) — mit korrektem `required`/`default:`
   nach der kotlinx-Regel aus §3.
2. Nur `web/types.ts` generieren (Option D) und gegen das heutige `types.ts` diffen — verifiziert,
   dass die Optionalität stimmt, bevor irgendwas verdrahtet wird.
3. Erst wenn D für alle Domänen stabil ist, Android-Moshi-DTOs dazunehmen.
4. Backend-DTOs zuletzt aus der Spec generieren (macht die Spec echt zur SSOT); vorher bleibt
   die Spec eine parallele Quelle.
5. Generator-Artefakte immer aus dem Prod-Build heraushalten; nur ein CI-Diff-Gate.

---

## 6. Konsequenzen

- **Kurzfristig:** kein Code-/Build-Change durch dieses ADR. Der Kontrakt bleibt handgepflegt;
  die bestehenden Guards (#134 etc.) bleiben die Absicherung.
- Der **Parität-Guard** ist als separates `test-gap`-Issue [#593](https://github.com/maxliem9/home-base/issues/593)
  angelegt — bewusst getrennt, damit dieses ADR eine reine Richtungsentscheidung bleibt.
- Die „4-Wege"-Formulierung in #547 ist präzisiert: **3-Wege** für Typen; `mockApi.ts` ist ein
  Consumer von `types.ts` und ein separates *Verhaltens*-Artefakt.
- **Status `Accepted`:** #547 war explizit eine dem Menschen vorbehaltene Richtungsentscheidung
  („sollte explizit entschieden werden"); am 2026-07-17 ratifiziert. #547 schließt mit dem Merge
  dieses PRs (`Closes #547`).
</content>
