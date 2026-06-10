-- Eigene Feiertage (#51) — household-wide, recurring-annual custom holidays.
-- Unlike the statutory holidays (computed per Bundesland in the client, not stored),
-- these are explicit rows the household maintains: a fixed month+day that recurs every
-- year, a label, and a whole/half-day flag. No user_id / Bundesland — they apply to
-- everyone (Heiligabend/Silvester count for the whole household).
CREATE TABLE custom_holidays (
    id    UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    month INTEGER      NOT NULL,
    day   INTEGER      NOT NULL,
    half  BOOLEAN      NOT NULL DEFAULT FALSE,
    label TEXT         NOT NULL
);

-- One holiday per (month, day) — a second row for the same calendar date is meaningless.
CREATE UNIQUE INDEX custom_holidays_month_day_uniq ON custom_holidays(month, day);

-- Seed the two motivating half-days; editable/removable like any other entry.
INSERT INTO custom_holidays (month, day, half, label) VALUES
    (12, 24, TRUE, 'Heiligabend'),
    (12, 31, TRUE, 'Silvester');
