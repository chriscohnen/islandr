# Backups to an S3 bucket

Islandr can write its whole configuration to an S3-compatible bucket and restore it from there — AWS S3, Cloudflare R2, Backblaze B2, MinIO. It talks to the bucket directly; nothing extra to install.

Open **Backup** in the System menu.

## Set up

1. Enter the bucket's endpoint, region, name and an access key, and save.
2. **Generate backup key.** The key is shown once. Write it down and keep it somewhere that survives this hub. Every backup is encrypted with it; without the key and without this hub, nobody can read them.
3. **Back up now**, or check that a file appears in the bucket.

A backup holds users, devices (including the private keys Islandr stores for them), networks, resources, roles and grants — the same data as the configuration export. Each backup gets a new name (`islandr-<host>-<UTC time>-<version>.islbak`); nothing is ever overwritten.

## Give Islandr an access key that cannot delete

Islandr never deletes a backup, and it should not be *able* to. A hub that is taken over can then fill the bucket but not erase what is in it. The key needs three permissions: write, read, list.

AWS IAM policy (replace the bucket name):

```json
{
  "Version": "2012-10-17",
  "Statement": [
    { "Effect": "Allow", "Action": ["s3:PutObject", "s3:GetObject"],
      "Resource": "arn:aws:s3:::my-islandr-backups/islandr-backups/*" },
    { "Effect": "Allow", "Action": "s3:ListBucket",
      "Resource": "arn:aws:s3:::my-islandr-backups" }
  ]
}
```

MinIO accepts the same policy document. Backblaze B2 lets you create an application key without the delete capability. For Cloudflare R2, check which token permission levels your account offers and pick the narrowest one that still allows writing and reading — if it includes delete, the lifecycle rule below still bounds the damage, but the protection above is weaker.

## Let the bucket expire old backups

Because Islandr does not delete, expiry is a lifecycle rule on the bucket. For example, keep ninety days (AWS; the other providers have an equivalent setting):

```json
{
  "Rules": [
    { "ID": "expire-islandr-backups", "Status": "Enabled",
      "Filter": { "Prefix": "islandr-backups/" },
      "Expiration": { "Days": 90 } }
  ]
}
```

## Restore — also onto a fresh install

On the new instance, open **Backup → Restore**, tick *Give the bucket's details for this restore*, and enter endpoint, bucket and access key. Show the backups, pick one, type the backup key, restore. The old hub is not needed.

A wrong or mistyped key is refused and changes nothing; the file is checked before any data is imported. The key tolerates spaces, dashes and capitals.

## Notes

- The bucket's access key and the backup key are stored encrypted in Islandr's database when `ISLANDR_ENCRYPTION_KEY` is set; otherwise they are stored as they are, and the page says so.
- Restoring replaces the configuration of the instance it runs on.
- Backups made by a newer Islandr than the one restoring them are refused, so a restore never quietly drops settings the older version does not know.
