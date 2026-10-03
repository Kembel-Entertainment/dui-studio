import { describe, it, expect } from 'vitest';
import { parse, flatten, setAttributes, duplicate, group, History } from '../src/lib/document';
import { jsonValue } from '../src/lib/json-document';
const source = `<?xml version="1.0"?>\n<!-- keep these comments -->\n<dui-menu width='240' height="90">\n <dui-layer>\n  <dui-button id='first' x="0" label="A &amp; B"/>\n  <!-- between -->\n  <dui-button id="second" x="8" label="{{profile.name}}"/>\n </dui-layer>\n</dui-menu>`;
describe('lossless authoring', () => {
  it('replaces only the chosen attribute and retains quoting, comments and bindings', () => {
    const n = flatten(parse(source).roots)[2];
    expect(setAttributes(source, n, { x: '24' })).toBe(source.replace('x="0"', 'x="24"'));
  });
  it('escapes attribute data once and decodes it for the inspector', () => {
    const n = flatten(parse(source).roots)[2];
    const edited = setAttributes(source, n, { label: `Don't <go> & "run"` });
    expect(flatten(parse(edited).roots)[2].attributes.find((a) => a.name === 'label')?.value).toBe(
      `Don't <go> & "run"`,
    );
  });
  it('duplicates with unique ids and preserves dynamic bindings', () => {
    const edited = duplicate(source, [flatten(parse(source).roots)[3]]);
    expect(edited).toContain('id="second_copy"');
    expect(edited.match(/\{\{profile.name}}/g)).toHaveLength(2);
  });
  it('groups siblings without discarding interstitial comments', () => {
    const edited = group(source, flatten(parse(source).roots).slice(2));
    expect(edited).toContain('<!-- between -->');
    expect(parse(edited).errors).toEqual([]);
    expect(edited).toContain('<dui-group>');
  });
  it('keeps incomplete edits representable without throwing', () => {
    expect(parse('<dui-menu><dui-button x="')).toMatchObject({ roots: [] });
  });
  it('undoes and redoes a whole visual operation', () => {
    const history = new History();
    history.push(source);
    expect(history.undo('new')).toBe(source);
    expect(history.redo(source)).toBe('new');
  });
  it('edits map geometry without reformatting surrounding JSON', () => {
    const source = '{ "layers": [ { "x": 12, "id": "target" } ] }';
    expect(jsonValue(source, ['layers', 0, 'x'], 24)).toBe(source.replace('12', '24'));
  });
});
