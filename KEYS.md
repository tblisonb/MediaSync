# Keys and passwords to keep safe

A few secrets live only on the machines that made them. Losing them won't break anything today,
but it makes recovery painful or impossible later. Keep each one in a password manager, and a
second copy somewhere else.

This file holds instructions only, never the secrets themselves.

| What | Where it lives | Without it |
| --- | --- | --- |
| **Android release key** (the maintainer only) | `~/.android/mediasync-release.jks` and `~/.android/mediasync-release.properties` on the PC that makes releases | New app releases can't install over the old ones: every phone has to uninstall MediaSync (losing its pairing) and install again. |
| **restic backup password** (anyone with backups) | `/etc/mediasync/backup/password` on the server | **The backups can't be read**, by anyone, ever. |
| **Backup location and login** (anyone with backups) | `/etc/mediasync/backup/repository` on the server | You can't reach the backups until you set a new login on the backup machine. That's fixable, but annoying when you're already recovering from a loss. |
| **Web UI admin password** | Only in your head, unless you save it | Reset it with `mediasync-server setup` (see the README). |

## What to save in Bitwarden (no Premium needed)

Without Premium, Bitwarden can't attach files, so save everything as text in **Secure Notes**.
A note holds up to 10,000 characters once encrypted, which is room enough.

### 1. "MediaSync Android release key"

Create a Secure Note with:

1. **Notes:** the key file as text. Copy it with:

   ```sh
   base64 -w0 ~/.android/mediasync-release.jks | wl-copy     # or: | xclip -selection clipboard
   ```

   Paste that into the note: one long line of about 5,700 characters.
2. **A hidden custom field `password`:** the key's password. It's the `storePassword=` line of the
   properties file (`keyPassword` is the same):

   ```sh
   grep '^storePassword=' ~/.android/mediasync-release.properties
   ```

3. **A text custom field `fingerprint`:** the certificate's SHA-256, so a restored key can be
   checked. `keytool` comes with the JDK (here, `~/Android/jdk-21/bin/keytool`):

   ```sh
   keytool -list -v -keystore ~/.android/mediasync-release.jks -storepass "$(sed -n 's/^storePassword=//p' ~/.android/mediasync-release.properties)" | grep -m1 SHA256:
   ```

   For the current key it starts `52:DA:EA:FE` and ends `C9:EE:9D`.

You don't need to save the properties file itself. It's four lines that can be rewritten from the
password (see [Restoring the release key](#restoring-the-release-key)).

### 2. "MediaSync backup (restic)"

Create a Secure Note with:

1. **A hidden custom field `password`:** the output of
   `sudo cat /etc/mediasync/backup/password`.
2. **A hidden custom field `repository`:** the output of
   `sudo cat /etc/mediasync/backup/repository`. It's a URL like
   `rest:https://mediasync:<login>@<machine>.<tailnet>.ts.net/mediasync/`, or a folder path for a
   local backup. The login in it is a secret too.

## A second copy, somewhere else

A password manager is one place; keep a second copy in case you lose access to it. A good way
is one encrypted file that you can put anywhere: a USB stick, another cloud drive, a relative's
computer.

```sh
# Bundle the four files into one archive, encrypted with a passphrase you choose
# (save the passphrase in Bitwarden as well).
mkdir -m 700 /tmp/mediasync-keys
cp ~/.android/mediasync-release.jks ~/.android/mediasync-release.properties /tmp/mediasync-keys/
sudo cat /etc/mediasync/backup/password > /tmp/mediasync-keys/restic-password
sudo cat /etc/mediasync/backup/repository > /tmp/mediasync-keys/restic-repository
tar czf - -C /tmp mediasync-keys | gpg --symmetric --cipher-algo AES256 -o mediasync-keys.tar.gz.gpg
rm -rf /tmp/mediasync-keys
```

To open it again: `gpg -d mediasync-keys.tar.gz.gpg | tar xzf -`.

A sheet of paper with the restic password, kept with your important documents, is a fine third
copy. The password is short enough to type back in.

Update the copies if a secret ever changes. That happens when you run `make-release-key.sh` on a
fresh machine (it refuses to replace an existing key), or move the backups and get a new
repository login.

## Restoring the release key

On a new PC, before releasing the app:

```sh
mkdir -p ~/.android
base64 -d > ~/.android/mediasync-release.jks       # paste the note's text, then press Ctrl-D
chmod 600 ~/.android/mediasync-release.jks
read -rs -p "Key password: " PW && echo
umask 077 && cat > ~/.android/mediasync-release.properties <<EOF
storeFile=$HOME/.android/mediasync-release.jks
storePassword=$PW
keyAlias=mediasync
keyPassword=$PW
EOF
unset PW
keytool -list -v -keystore ~/.android/mediasync-release.jks -storepass "$(sed -n 's/^storePassword=//p' ~/.android/mediasync-release.properties)" | grep -m1 SHA256:
```

The fingerprint printed last must match the one in the note.

## Restoring the server from a backup

If the server PC is lost, set up a new one:

1. Install restic, then point it at the backup using the note's two values:

   ```sh
   export RESTIC_REPOSITORY='<repository from the note>'
   read -rs -p "restic password: " RESTIC_PASSWORD && export RESTIC_PASSWORD && echo
   restic snapshots                     # the nightly backups
   sudo -E restic restore latest --target /var/lib/mediasync-restore   # the size of your library
   ```

2. Put the repository in place (it was backed up from `/var/lib/mediasync`, or your `--repo`
   folder). The live database isn't in the backup, so use the consistent copy made just before each
   backup:

   ```sh
   sudo mv /var/lib/mediasync-restore/var/lib/mediasync /var/lib/mediasync
   sudo rm -r /var/lib/mediasync-restore
   sudo mv /var/lib/mediasync/snapshots/repo.db /var/lib/mediasync/repo.db
   ```

3. Install the server as usual. It sees the existing repository and keeps it, along with your
   admin password and devices. If the mediasync user doesn't exist yet, the installer creates it;
   then hand it the files and check them:

   ```sh
   sudo mediasync-server-<version>/install.sh
   sudo chown -R mediasync:mediasync /var/lib/mediasync
   sudo systemctl restart mediasync
   sudo -u mediasync /opt/mediasync/bin/mediasync-server fsck --full --repo /var/lib/mediasync
   ```

If the new PC has a different tailnet name, the devices need the new address: pair them again.
