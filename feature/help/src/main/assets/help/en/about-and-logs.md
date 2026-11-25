# About and Logs

The About page contains version information, community links, diagnostic tools, the Developer Options unlock entry, and open-source licenses. Donation options have their own page.

## Licenses and source code

Open **Settings → About → Open-source components and licenses**, then select **TinaIDE** or
**RikkaHub (TinaIDE embedded)** to read the bundled GPL / AGPL license text offline and open the source repository.
RikkaHub now uses upstream's plain AGPL-3.0 license, without the former non-commercial or user-count restrictions.
Distribution still requires source matching the APK. Use the recursive checkout instructions on the Release page;
GitHub's ordinary source ZIP does not include submodules.

## Support the project

Open **Profile → Donate**, between **Settings** and **About**. This page shows WeChat and Alipay donation codes and a link to Afdian to support continued development and maintenance.

## Logs

You can view, export, or upload diagnostic logs. Anonymous uploads may include device and runtime information.

Review a log before sharing it. Project paths, command output, package names, and other environment details may still be sensitive.

## Feedback and attachments

**Settings → About → Feedback** sends only your message, optional contact details, app version, and anonymous installation reference by default.
Device details and logs are sent only after enabling **Attach logs and device information**. Redacted logs may still contain project paths, build commands, and PRoot output.
Selecting your own archive does not generate additional runtime logs or add a device field.

The selected attachment survives rotation and Activity recreation. If the system removes its cached copy, submission asks you to select it again; it never substitutes other logs.
Removing the attachment, cancelling feedback, or successfully submitting deletes the local copy. Failed submissions retain it for retry. Later operations reclaim unused imported files older than seven days.

## Developer Options

Repeatedly tapping the version information unlocks Developer Options when the feature is available. These options include diagnostic logging, server-address debugging, test tools, and bundled-package reinstallation. Private identity and commercial features are not included in the open-source app.

## Related documentation

- [Settings overview](settings-overview.md)
- [Known issues](known-issues.md)
