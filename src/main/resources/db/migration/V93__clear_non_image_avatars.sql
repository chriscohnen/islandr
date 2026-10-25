-- oidc-avatar-ssrf-xss: until 1.2.0 an avatar fetched from the OIDC picture
-- URL, MS Graph or Gravatar was stored with whatever Content-Type the remote
-- server sent. A row whose type is not one of the four image types may hold
-- an HTML or other answer and is cleared. The avatar comes back on the next
-- login, now sniffed from its bytes; until then the user shows initials.
-- Parameters such as "; charset=..." count as not-an-image here on purpose:
-- the fetcher never writes them any more, and a re-fetch is cheap.
UPDATE users
SET avatar_bytes = NULL,
    avatar_content_type = NULL,
    avatar_etag = NULL,
    avatar_fetched_at = NULL,
    avatar_source = NULL
WHERE avatar_content_type IS NOT NULL
  AND lower(avatar_content_type) NOT IN ('image/png', 'image/jpeg', 'image/gif', 'image/webp');
