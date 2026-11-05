<!--
  Copyright 2026, Nguyen-Do Consulting

  Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
-->

# Signing commits (SSH)

Signed commits show a green **Verified** badge on GitHub, proving they came from
us and not from someone who set their git email to ours.

These steps set up signing on one Windows computer, in PowerShell. Repeat them on
every computer you commit from. **Give each computer its own key** rather than
copying one around, so a lost machine only means deleting that one key on GitHub.

The signing settings are made **only in this repo** (no `--global`), so commits
to other repos, such as Bitbucket ones, are never signed. This repo commits as
the GitHub no-reply address `22156177+NguyenDo-Consulting@users.noreply.github.com`
(also set only in this repo), and GitHub treats that address as verified for the
NguyenDo-Consulting account.

## 1. Generate the key

```powershell
ssh-keygen -t ed25519 -C "22156177+NguyenDo-Consulting@users.noreply.github.com" -f "$HOME\.ssh\id_ed25519_nguyendo"
```

Enter a passphrase when prompted. If it says the file already exists, answer
**n** and continue with step 2; this computer already has a NguyenDo key.

The key gets its own name rather than the default `id_ed25519`, so it never mixes
with keys for other accounts (Bitbucket, work): SSH only offers default-named keys
to servers automatically, and this one is used only where these steps point to it.
The `-C` text is just a label stored in the key.

## 2. Start ssh-agent

This means you type the passphrase once, not on every commit.

In an **Administrator** PowerShell, once per computer:

```powershell
Get-Service ssh-agent | Set-Service -StartupType Automatic
Start-Service ssh-agent
```

Then in a normal PowerShell:

```powershell
ssh-add "$HOME\.ssh\id_ed25519_nguyendo"
```

The agent remembers the key across reboots.

## 3. Configure this repo to sign its commits

Run these **inside the repo folder** (e.g. `cd C:\Projects\Lawnchair`). Without
`--global`, git writes them to this repo's `.git/config` only.

```powershell
$ssh = ($HOME -replace '\\','/') + '/.ssh'
git config user.email "22156177+NguyenDo-Consulting@users.noreply.github.com"
git config gpg.format ssh
git config user.signingkey "$ssh/id_ed25519_nguyendo.pub"
git config gpg.ssh.program "C:/Windows/System32/OpenSSH/ssh-keygen.exe"
git config commit.gpgsign true
git config tag.gpgsign true
```

`.git/config` isn't part of the repo's history, so a fresh clone (or another
computer) needs this step again.

`gpg.ssh.program` makes git use Windows' OpenSSH, the same one the agent from
step 2 uses. Without it, git may use its own bundled copy, which can't see the
agent.

## 4. Let git verify signatures locally

Optional, but this makes the test in step 6 work. If you're in a new PowerShell
session, run the `$ssh = ...` line from step 3 again first.

```powershell
"22156177+NguyenDo-Consulting@users.noreply.github.com $(Get-Content "$HOME\.ssh\id_ed25519_nguyendo.pub")" | Out-File -Encoding ascii "$HOME\.ssh\allowed_signers"
git config gpg.ssh.allowedSignersFile "$ssh/allowed_signers"
```

## 5. Add the key to GitHub

```powershell
Get-Content "$HOME\.ssh\id_ed25519_nguyendo.pub" | Set-Clipboard
```

Then, signed in as **NguyenDo-Consulting**, go to **GitHub → Settings → SSH and
GPG keys → New SSH key**:

- **Title:** the computer's name, e.g. "Work laptop"
- **Key type:** **Signing Key**
- **Key:** paste

The no-reply email from step 3 is verified automatically; nothing to add under
**Settings → Emails**.

## 6. Test it

Inside the repo, make an empty commit, check it, then undo it:

```powershell
git commit --allow-empty -m "signing test"
git log --show-signature -1
git reset --soft HEAD~1
```

Look for `Good "git" signature for 22156177+NguyenDo-Consulting@users.noreply.github.com`.
The `reset --soft` removes only the empty test commit; your files and staged
changes are untouched. Don't push the test commit.

To confirm other repos are unaffected, run this in any Bitbucket repo. It should
print nothing:

```powershell
git config commit.gpgsign
```

After that, every commit in this repo, including ones made from VS Code, is
signed and shows **Verified** on GitHub once pushed.

## Notes

- Only new commits are signed. Existing history stays unsigned; don't rewrite
  shared branches to change that.
- Removing a key from GitHub later doesn't un-verify commits it already signed.
- Never commit, email, or sync the private key (`id_ed25519_nguyendo`, without `.pub`).
  Only the `.pub` file is safe to share.
