# rsync Synchronization Setup

PROJECT synchronization can use an rsync daemon on the development computer for incremental transfer.

## Linux or macOS

Create /etc/rsyncd.conf:

    pid file = /var/run/rsyncd.pid
    lock file = /var/run/rsync.lock
    log file = /var/log/rsyncd.log

    [tina-workspace]
        path = /tmp/tina-workspace
        read only = no
        list = yes
        uid = your-user
        gid = your-group
        comment = TinaIDE Project Workspace

Create the workspace and start the daemon:

    mkdir -p /tmp/tina-workspace
    sudo rsync --daemon --config=/etc/rsyncd.conf

Allow TCP port 873 in the firewall when required.

## Windows

WSL2 is the recommended approach: install rsync in WSL and follow the Linux configuration. cwRsync can also host a Windows path:

    use chroot = false
    strict modes = false

    [tina-workspace]
        path = C:\TinaWorkspace
        read only = no
        list = yes

## Verify the daemon

    rsync rsync://localhost/

The output should list tina-workspace.

## Device settings

1. Open **Settings → Language Server**.
2. Select PROJECT synchronization mode.
3. Select RSYNC synchronization.
4. Enter module tina-workspace.
5. Enter port 873.

## Security

- Use an rsync daemon only on a trusted network.
- Restrict clients with hosts allow.
- Do not expose an unauthenticated writable daemon to the public internet.
- For remote access, prefer a secured tunnel or VPN.

## Troubleshooting

- Timeout: verify the daemon and firewall.
- Permission denied: verify the workspace owner and uid/gid.
- Module missing: confirm the module name and restart the daemon.

## Related documentation

- [Remote LSP](remote-lsp-guide.md)
- [LSP settings](lsp-settings.md)
