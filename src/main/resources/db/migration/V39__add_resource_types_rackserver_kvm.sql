-- 0.11.0 fuegte die Resource-Typen 'rackserver' und 'kvm' hinzu (Backend-@Pattern
-- in ResourceDto + GUI), aber die CHECK-Constraint auf resources.type stammt noch
-- aus V13 und kennt sie nicht. Ein UPDATE auf type='kvm'/'rackserver' kracht daher
-- mit SQLITE_CONSTRAINT_CHECK (HTTP 500).
--
-- SQLite kann eine CHECK-Constraint nicht per ALTER aendern — sie ist Teil der
-- Table-Definition. Gleiche Rebuild-Reihenfolge wie V13: neue Tabelle mit der
-- erweiterten Liste anlegen, Daten kopieren (alle bestehenden Typen sind in der
-- neuen Liste weiterhin gueltig — kein Relabeling noetig), alte droppen, umbenennen.
CREATE TABLE resources_v39 (
    id          VARCHAR(36)  NOT NULL PRIMARY KEY,
    site_id     VARCHAR(36)  NOT NULL REFERENCES sites(id),
    name        VARCHAR(255) NOT NULL,
    ip          VARCHAR(45)  NOT NULL,
    description TEXT         NULL,
    type        VARCHAR(16)  NOT NULL DEFAULT 'computer'
        CHECK (type IN ('computer','router','printer','nas','camera','iot','virt-host','rackserver','kvm','management','other')),
    created_at  TIMESTAMP    NOT NULL
);

INSERT INTO resources_v39 (id, site_id, name, ip, description, type, created_at)
SELECT id, site_id, name, ip, description, type, created_at
FROM resources;

DROP INDEX ix_resources_site;
DROP INDEX ix_resources_site_ip;
DROP TABLE resources;
ALTER TABLE resources_v39 RENAME TO resources;
CREATE INDEX ix_resources_site ON resources (site_id);
CREATE UNIQUE INDEX ix_resources_site_ip ON resources (site_id, ip);
