import { NAV_ENTRIES, NavLink, isNavGroup, isPathInGroup, toSections, userInitials } from './nav-entries';

describe('nav-entries', () => {
  it('fasst aufeinanderfolgende Einträge gleichen Abschnitts zusammen', () => {
    const links: NavLink[] = [
      { path: '/a', label: 'A' },
      { path: '/b', label: 'B', section: 'X' },
      { path: '/c', label: 'C', section: 'X' },
      { path: '/d', label: 'D', section: 'Y' }
    ];
    expect(toSections(links).map(s => [s.title, s.links.length])).toEqual([
      [undefined, 1], ['X', 2], ['Y', 1]
    ]);
  });

  it('erkennt Unterseiten und ignoriert Query-Parameter, aber keine Namensvettern', () => {
    expect(isPathInGroup('/admin?tab=konten', ['/admin'])).toBeTrue();
    expect(isPathInGroup('/admin/users', ['/admin'])).toBeTrue();
    expect(isPathInGroup('/administration', ['/admin'])).toBeFalse();
    expect(isPathInGroup('/calendar', ['/'])).toBeFalse();
    expect(isPathInGroup('/', ['/'])).toBeTrue();
  });

  it('führt jeden Pfad genau einmal', () => {
    const paths = NAV_ENTRIES.flatMap(entry => isNavGroup(entry) ? entry.children.map(c => c.path) : [entry.path]);
    expect(new Set(paths).size).toBe(paths.length);
  });

  it('bildet Initialen aus erstem und letztem Wort', () => {
    expect(userInitials('Benedikt Lind')).toBe('BL');
    expect(userInitials('Anna Maria Schmidt')).toBe('AS');
    expect(userInitials('administrator')).toBe('A');
    expect(userInitials('  ')).toBe('?');
  });

  it('hält die Hauptleiste kurz', () => {
    expect(NAV_ENTRIES.length).toBeLessThanOrEqual(7);
  });
});
