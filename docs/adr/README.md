# Architecture Decision Records (ADR)

Kurze, dauerhafte Notizen zu **Richtungsentscheidungen** mit Langzeitfolgen —
das „Warum" hinter einer Wahl, das man später nicht mehr aus dem Code rekonstruieren
kann. Nicht jede Änderung braucht ein ADR; ein ADR lohnt sich, wenn eine Entscheidung
laufende Kosten trägt, mehrere Alternativen gegeneinander abgewogen wurden oder sie
bewusst offengehalten/verschoben wird.

## Konvention (bewusst minimal)

- Eine Datei je Entscheidung: `NNNN-kurzer-slug.md` (fortlaufende 4-stellige Nummer).
- Kopf-Metadaten: **Status**, **Datum**, ggf. verlinkte Issues.
- **Status-Werte:** `Proposed` (Empfehlung steht, Mensch muss noch ratifizieren) ·
  `Accepted` · `Rejected` · `Superseded by NNNN` · `Deferred` (bewusst vertagt).
- Gliederung: *Kontext / Problem → Optionen (mit Trade-offs) → Entscheidung →
  Konsequenzen*. Kurz halten; Details gehören in die Feature-Docs unter `docs/`.
- Angenommene ADRs sind **append-only**: nicht umschreiben, sondern durch ein neues
  ADR ablösen (`Superseded by`).

## Index

| Nr. | Titel | Status |
|---|---|---|
| [0001](0001-api-contract-single-source-of-truth.md) | API-Kontrakt: Single Source of Truth vs. Drift-Guard | Proposed (#547) |
</content>
</invoke>
