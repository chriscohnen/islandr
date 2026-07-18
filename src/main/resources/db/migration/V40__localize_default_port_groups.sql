-- V40: localize the seeded default port groups to English.
-- V10 seeded these with German names/descriptions/labels, which showed through
-- in the English UI (the app is otherwise fully DE/EN). Update only the rows that
-- still carry the original German seed values (matched by stable UUID + value),
-- so an operator who renamed or edited a group is never clobbered. Groups already
-- in English (Web_Standard, RDP, SSH, SMB names; IPP / HTTP / HTTPS labels) are
-- left untouched.

UPDATE port_groups
   SET name        = 'Printer_Standard_Ports',
       description = 'Network printer: RAW (JetDirect) + IPP'
 WHERE id = '00000000-0000-0000-0000-port-group-prn'
   AND name = 'Drucker_Standard_Ports';

UPDATE port_groups
   SET description = 'Windows Remote Desktop'
 WHERE id = '00000000-0000-0000-0000-port-group-rdp'
   AND description = 'Windows-Remote-Desktop';

UPDATE port_groups
   SET description = 'Windows file sharing'
 WHERE id = '00000000-0000-0000-0000-port-group-smb'
   AND description = 'Windows-Dateifreigabe';

UPDATE port_group_members
   SET label = 'JetDirect / RAW printing'
 WHERE id = '00000000-0000-0000-0000-pgm-prn-9100'
   AND label = 'JetDirect / RAW-Druck';
