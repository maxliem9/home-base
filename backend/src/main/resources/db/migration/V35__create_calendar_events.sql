-- Termin-/Event-Entität für den Familienkalender (#434, Folge aus #427). Ein echtes
-- terminiertes Ereignis (Arzt, Tierarzt, Geburtstag …) — bisher kannte der Kalender nur
-- Todo-Fälligkeiten/Abwesenheiten/Kita/Wochenplan als Overlay, aber kein eigenständiges
-- Event. Leichtgewichtig, Vorbild MealPlan/Absence: haushaltsweit geteilt (kein Eigentümer-
-- Check; created_by ist nur Herkunft), Range-Endpunkt, eigener WS-Kanal.
--
-- Zeitmodell: `all_day` trennt ganztägige Events (Geburtstag) von terminierten. Bei
-- all_day=FALSE darf eine optionale Uhrzeit (start_time / optional end_time) gesetzt sein;
-- bei all_day=TRUE bleiben beide Zeiten NULL. Wiederholung (analog wiederkehrende Todos,
-- kein RRULE) ist bewusst einem Folge-Issue vorbehalten.
CREATE TABLE calendar_events (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    title      VARCHAR(200) NOT NULL,
    -- Event-Art für die farbcodierte Darstellung im Kalender (#427-Integration folgt).
    type       VARCHAR(20)  NOT NULL DEFAULT 'OTHER',
    date       DATE         NOT NULL,
    all_day    BOOLEAN      NOT NULL DEFAULT TRUE,
    start_time TIME,
    end_time   TIME,
    location   TEXT,
    notes      TEXT,
    created_by VARCHAR(50)  NOT NULL REFERENCES users(username),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    -- Ganztägige Events tragen keine Uhrzeit; terminierte dürfen start/end setzen (beide
    -- optional, end ohne start ist nicht erlaubt). Reihenfolge end >= start prüft die App
    -- (Zeit ohne Datum lässt sich hier nicht sauber über Mitternacht spannen).
    CONSTRAINT calendar_events_allday_no_time
        CHECK (NOT all_day OR (start_time IS NULL AND end_time IS NULL)),
    CONSTRAINT calendar_events_end_needs_start
        CHECK (end_time IS NULL OR start_time IS NOT NULL)
);

-- Die Kalenderansicht lädt einen Datums-Range (GET /events?from=&to=).
CREATE INDEX calendar_events_date_idx ON calendar_events(date);
