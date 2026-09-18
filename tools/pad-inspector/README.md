# Pad Inspector

Pad Inspector collects device, display, foreground-window, UI-tree, and screenshot evidence from exactly one ready ADB device. Reports are written only to:

`~/.config/yishijieyongzhe/private/pad-reports/`

It never prints the ADB serial/endpoint. A target package must come from a confirmed local value; if omitted, the current foreground package is observed rather than guessed.

```bash
python3 tools/pad-inspector/pad_inspector.py capture --label baseline
TARGET_GAME_PACKAGE=confirmed.package.name \
  python3 tools/pad-inspector/pad_inspector.py capture --label normal-window
python3 tools/pad-inspector/pad_inspector.py compare /private/report/one /private/report/two
python3 -m unittest tools/pad-inspector/test_pad_inspector.py
```

Do not copy raw reports, screenshots, UI trees, package names, serials, or network endpoints into the public repository.
