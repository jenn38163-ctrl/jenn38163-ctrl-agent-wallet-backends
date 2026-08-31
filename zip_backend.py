import os, zipfile
base = "backend"
zip_path = "agent-wallet-backend-v0.3.zip"
with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as z:
    for root, _, names in os.walk(base):
        if "node_modules" in root or "dist" in root:
            continue
        for n in names:
            p = os.path.join(root, n)
            z.write(p, os.path.relpath(p, base))
print(f"Created {zip_path}")
