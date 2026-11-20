import os, zipfile

base = "backend"
zip_path = "agent-wallet-backend-v0.3.zip"
skip_dirs = {"node_modules", "dist", ".git"}

with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as z:
    for root, dirs, names in os.walk(base):
        dirs[:] = [d for d in dirs if d not in skip_dirs]
        for n in sorted(names):
            if n.endswith(".log") or n == ".env" or (n.startswith(".env.") and n != ".env.example"):
                continue
            p = os.path.join(root, n)
            z.write(p, os.path.relpath(p, base))
print(f"Created {zip_path}")
