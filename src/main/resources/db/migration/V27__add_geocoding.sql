-- V27: Nominatim URL in settings (empty = geocoding disabled)
--      location_label on sites (human-readable place name from geocoder)
ALTER TABLE settings ADD COLUMN nominatim_url VARCHAR(255);
ALTER TABLE sites ADD COLUMN location_label VARCHAR(255);
