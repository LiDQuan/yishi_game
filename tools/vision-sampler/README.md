# Vision Sampler

从本地私有截图按已确认 viewport 和 normalized ROI 裁剪最小模板：

```bash
python3 tools/vision-sampler/vision_sampler.py crop \
  --image ~/.config/yishijieyongzhe/private/vision/screen.png \
  --viewport 100,20,1100,1700 \
  --roi 0.70,0.80,0.95,0.95 \
  --id page.example.anchor \
  --page-id EXAMPLE
```

输出默认留在 `~/.config/yishijieyongzhe/private/vision/templates/`，目录为 `0700`、文件为 `0600`。metadata 的 `privacyReview` 固定为 `PENDING`；工具不会把截图或模板复制到仓库。公开模板必须另行人工检查并确认不含账号、角色名、聊天、通知、二维码、设备或网络信息。

依赖 Pillow，仅用于开发机裁剪：`python3 -m pip install Pillow`。
