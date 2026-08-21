# Git Settings

The Git settings page separates HTTPS credentials from SSH key management.

## HTTPS credentials

You can add, edit, and remove credentials by host. A record contains:

- Host
- Username
- Token

Host input is normalized, so github.com, https://github.com, and a repository URL can resolve to the same host.

For a new credential, Token is required. While editing an existing credential, leaving Token empty keeps the saved token. An empty Username falls back to oauth2.

HTTPS credentials apply only when the repository remote uses HTTPS.

## SSH keys

The SSH tab supports:

- generating an Ed25519 key;
- importing a private key;
- choosing a default key;
- resetting trusted server host keys;
- listing and inspecting keys;
- copying a public key;
- deleting a non-protected key;
- binding a key and port to a host.

Generated public keys use the standard OpenSSH `ssh-ed25519` format and can be copied directly to GitHub, GitLab, or Gitee. Bindings are keyed by both host and port, so port 22 and port 2222 can use separate entries for the same host.

Private-key imports are limited to 1 MiB. Key names must be 1-64 characters, start with a letter or digit, and contain only letters, digits, `.`, `_`, or `-`.

Use the default key when one identity handles most repositories. Use host bindings for multiple accounts, company GitLab instances, or non-standard ports.

## Choosing HTTPS or SSH

- **HTTPS** — fast setup with a personal access token.
- **SSH** — better for long-term use with multiple repositories, hosts, or identities.

## Troubleshooting

### HTTPS credential has no effect

Confirm that the host was normalized correctly, the token has permission, and the remote URL actually uses HTTPS.

### The default SSH key is wrong for one host

Create a host binding for that host, key, and optional custom port.

### An imported key is not selected

Set it as the default or bind it to the relevant host.

### The server host key changed

Confirm the change through a trusted channel first, then use **Reset trusted host keys** in SSH settings. The next connection records the server key again. Deleting a host binding also removes the trust record for that host and port.

## Related documentation

- [Git basics](git-basics.md)
- [Settings overview](settings-overview.md)
- [Terminal usage](terminal-usage.md)
- [Known issues](known-issues.md)
