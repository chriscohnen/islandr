-- V54: admin-editable DNS name per resource (ADR-0023, MVP — manual entry only,
-- no automatic name discovery yet). Null until an admin sets one; a resource
-- without a dns_name simply never resolves through the DNS resolver, no other
-- effect (IP-based access and every existing feature keep working unchanged).
ALTER TABLE resources ADD COLUMN dns_name VARCHAR(63);
