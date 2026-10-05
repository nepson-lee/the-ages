import * as THREE from "three";

/** 各區域的地形與氣氛。目前是程式產生的佔位場景，之後換成 glTF 模型時只要改這個檔。 */
export interface ZoneTheme {
  sky: number;
  fogNear: number;
  fogFar: number;
  ground: number;
  /** 半球光強度：森林比較暗。 */
  ambient: number;
  props(size: number): THREE.Object3D;
}

const THEMES: Record<string, ZoneTheme> = {
  "newbie-village": {
    sky: 0xb8c7d6,
    fogNear: 30,
    fogFar: 90,
    ground: 0x7a9a5a,
    ambient: 1.6,
    props: villageProps,
  },
  "dark-pine-forest": {
    sky: 0x5d6b66,
    fogNear: 14,
    fogFar: 55,
    ground: 0x3f5a3a,
    ambient: 1.0,
    props: forestProps,
  },
};

const FALLBACK: ZoneTheme = { ...THEMES["newbie-village"], props: () => new THREE.Group() };

export function themeFor(zoneId: string): ZoneTheme {
  return THEMES[zoneId] ?? FALLBACK;
}

/** 固定種子的亂數：每位玩家看到的擺設都一樣。 */
function seeded(seed: number): () => number {
  return () => ((seed = (seed * 16807) % 2147483647) / 2147483647);
}

function tree(trunkColor: number, leafColor: number, height: number, radius: number): THREE.Group {
  const t = new THREE.Mesh(new THREE.CylinderGeometry(0.15, 0.22, height * 0.35), new THREE.MeshStandardMaterial({ color: trunkColor }));
  t.position.y = height * 0.175;
  const l = new THREE.Mesh(new THREE.ConeGeometry(radius, height * 0.75, 8), new THREE.MeshStandardMaterial({ color: leafColor }));
  l.position.y = height * 0.35 + height * 0.3;
  t.castShadow = l.castShadow = true;
  return new THREE.Group().add(t, l);
}

/** 新手村：中央古井與周圍稀疏的樹。 */
function villageProps(size: number): THREE.Group {
  const props = new THREE.Group();
  const well = new THREE.Mesh(
    new THREE.CylinderGeometry(1.2, 1.3, 0.9, 20, 1, true),
    new THREE.MeshStandardMaterial({ color: 0x8a8a8a }),
  );
  well.position.y = 0.45;
  well.castShadow = true;
  props.add(well);

  const random = seeded(4444);
  for (let i = 0; i < 40; i++) {
    const angle = random() * Math.PI * 2;
    const radius = 8 + random() * (size / 2 - 10);
    const t = tree(0x6b4a2b, 0x3f6b35, 3, 0.9);
    t.position.set(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);
    t.scale.setScalar(0.8 + random() * 0.6);
    // 留出往北的小徑
    if (Math.abs(t.position.x) < 3 && t.position.z < -15) {
      continue;
    }
    props.add(t);
  }
  return props;
}

/** 黑松林：密集的高大黑松，中間留一條南北向的林道。 */
function forestProps(size: number): THREE.Group {
  const props = new THREE.Group();
  const random = seeded(1999);
  const half = size / 2;
  for (let i = 0; i < 160; i++) {
    const x = (random() * 2 - 1) * (half - 1);
    const z = (random() * 2 - 1) * (half - 1);
    if (Math.abs(x) < 3.5 || Math.hypot(x - 3, z - 31) < 5) {
      continue; // 林道與獵人小屋前的空地
    }
    const t = tree(0x3b2b1e, 0x1f3524, 6, 1.4);
    t.position.set(x, 0, z);
    t.scale.setScalar(0.7 + random() * 0.7);
    props.add(t);
  }
  // 林道上的落葉
  const path = new THREE.Mesh(new THREE.PlaneGeometry(5, size), new THREE.MeshStandardMaterial({ color: 0x5a4a32 }));
  path.rotation.x = -Math.PI / 2;
  path.position.y = 0.01;
  path.receiveShadow = true;
  props.add(path);
  return props;
}
