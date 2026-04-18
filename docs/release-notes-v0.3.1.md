## FenixHub v0.3.1

### Highlights
- Desktop parity update (Windows + Linux): firewall status and allow-rule workflow is now available on both platforms.
- Windows Defender path added with UAC prompt support for inbound TCP allow rule.
- Linux keeps pkexec/ufw/iptables firewall flow; frontend modal now adapts by platform.
- Android now requests runtime permissions proactively in identity/profile/direct flows.
- Overlay permission routing improved during Android identity setup/profile activation.

### Packaging
- Windows installer built through custom pipeline:
  - packaging/build-release.ps1
  - packaging/windows-stage as staged payload

### Notes
- Windows virtual-file drag and drop interception remains Windows-specific by design (WebView2/COM path). Linux keeps native URI/file drop path parity for standard desktop apps.
