"""Check that vendored Backdrop optics are upstream-identical except four compatibility shims."""
from pathlib import Path
import hashlib
import json

root = Path(__file__).resolve().parent.parent / 'third-party/backdrop'
patches = {
    'java/com/kyant/backdrop/LayerRecorder.kt': [
        ('internal fun DrawScope.recordLayer(\n    node: DelegatableNode,\n',
         'context(node: DelegatableNode)\ninternal fun DrawScope.recordLayer(\n')],
    'java/com/kyant/backdrop/DrawBackdropModifier.kt': [
        ('recordLayer(\n                this@DrawBackdropNode,\n                layer,',
         'recordLayer(\n                layer,'),
        ('recordLayer(this@DrawBackdropNode, layer)', 'recordLayer(layer)')],
    'java/com/kyant/backdrop/backdrops/LayerBackdropModifier.kt': [
        ('recordLayer(this@LayerBackdropNode, backdrop.graphicsLayer)', 'recordLayer(backdrop.graphicsLayer)')],
    'java/com/kyant/backdrop/InverseLayerScope.kt': [
        ('    var blendMode:', '    override var blendMode:'),
        ('    var colorFilter:', '    override var colorFilter:')],
}
manifest = json.loads((root / 'upstream-hashes.json').read_text('utf-8'))
actual = {p.relative_to(root / 'src/main').as_posix() for p in (root / 'src/main').rglob('*') if p.is_file()}
assert actual == set(manifest), 'Upstream source file inventory changed'
for name, digest in manifest.items():
    text = (root / 'src/main' / name).read_text('utf-8')
    for local, original in patches.get(name, []):
        assert text.count(local) == 1, f'Unexpected compatibility patch: {name}'
        text = text.replace(local, original)
    assert hashlib.sha256(text.encode()).hexdigest() == digest, f'Upstream source drift: {name}'
print(f'Backdrop 1.0.4: {len(manifest)} upstream files verified; four explicit compatibility shims; optics unchanged.')
