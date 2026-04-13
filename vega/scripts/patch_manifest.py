import re
import sys
import json
import os

proj_dir = sys.argv[1] if len(sys.argv) > 1 else '/tmp/vega-build'

manifest_path = os.path.join(proj_dir, 'manifest.toml')
package_path = os.path.join(proj_dir, 'package.json')
app_path = os.path.join(proj_dir, 'app.json')

print("=== Patching manifest.toml ===")
content = open(manifest_path).read()
print("BEFORE:", content)

if re.search(r'build-number', content):
    content = re.sub(r'build-number\s*=\s*\d+', 'build-number = 1', content)
else:
    content = re.sub(
        r'(version\s*=\s*"[^"]*")',
        r'\1\nbuild-number = 1',
        content,
        count=1
    )

if re.search(r'(?<![a-zA-Z-])version\s*=\s*"', content):
    content = re.sub(r'(?<![a-zA-Z-])version\s*=\s*"[^"]*"', 'version = "1.0.0"', content, count=1)

content = re.sub(r'id\s*=\s*"[^"]*"', 'id = "com.nexuscast.player"', content, count=1)

open(manifest_path, 'w').write(content)
print("AFTER:", content)

print("=== Patching package.json ===")
pkg = json.loads(open(package_path).read())
print("BEFORE version:", pkg.get('version'), "kepler:", pkg.get('kepler'))
pkg['version'] = '1.0.0'
if 'kepler' not in pkg:
    pkg['kepler'] = {}
pkg['kepler']['buildNumber'] = 1
pkg['kepler']['versionCode'] = 1
open(package_path, 'w').write(json.dumps(pkg, indent=2))
print("AFTER kepler:", pkg['kepler'])

if os.path.exists(app_path):
    print("=== Patching app.json ===")
    app = json.loads(open(app_path).read())
    print("BEFORE:", app)
    app['version'] = '1.0.0'
    app['buildNumber'] = '1'
    app['versionCode'] = 1
    open(app_path, 'w').write(json.dumps(app, indent=2))
    print("AFTER:", app)
