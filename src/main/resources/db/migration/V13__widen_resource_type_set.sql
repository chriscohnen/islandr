-- Erweitert das Resource-Typ-Set um Praxis-Faelle: router (inkl. Switch),
-- camera, iot, virt-host, management, other.
--
-- SQLite kennt kein ALTER TABLE ... DROP/ADD CONSTRAINT — die CHECK-Constraint
-- ist Teil der Table-Definition und bleibt aktiv, solange die Tabelle
-- existiert. Versucht man waehrend dieser Aktivitaet ein UPDATE auf einen
-- Wert ausserhalb der alten Liste (z.B. 'switch' -> 'router'), kracht es.
--
-- Korrekte Reihenfolge: neue Tabelle mit der neuen CHECK-Liste anlegen,
-- Daten beim INSERT umlabeln (CASE), alte Tabelle droppen, neue umbenennen.
-- So sieht kein einzelnes Statement die alte Constraint mit einem fuer sie
-- ungueltigen Wert.
CREATE TABLE resources_v13 (
    id          VARCHAR(36)  NOT NULL PRIMARY KEY,
    site_id     VARCHAR(36)  NOT NULL REFERENCES sites(id),
    name        VARCHAR(255) NOT NULL,
    ip          VARCHAR(45)  NOT NULL,
    description TEXT         NULL,
    type        VARCHAR(16)  NOT NULL DEFAULT 'computer'
        CHECK (type IN ('computer','router','printer','nas','camera','iot','virt-host','management','other')),
    created_at  TIMESTAMP    NOT NULL
);

-- Daten kopieren — der alte 'switch'-Typ wird beim Insert auf 'router' gemappt.
-- Alle anderen Werte bleiben unveraendert; sie sind in der neuen CHECK-Liste
-- weiterhin gueltig.
INSERT INTO resources_v13 (id, site_id, name, ip, description, type, created_at)
SELECT id, site_id, name, ip, description,
       CASE WHEN type = 'switch' THEN 'router' ELSE type END,
       created_at
FROM resources;

DROP INDEX ix_resources_site;
DROP INDEX ix_resources_site_ip;
DROP TABLE resources;
ALTER TABLE resources_v13 RENAME TO resources;
CREATE INDEX ix_resources_site ON resources (site_id);
CREATE UNIQUE INDEX ix_resources_site_ip ON resources (site_id, ip);
