-- Ad-hoc temporary direct grants (issue #70): an optional expiry on a
-- direct User-Resource grant (ADR-0024). NULL = permanent, unchanged
-- default behavior. A background job (UserGrantExpiryJob) auto-revokes
-- grants once valid_until has passed.
ALTER TABLE user_resource_grants ADD COLUMN valid_until TIMESTAMP;
