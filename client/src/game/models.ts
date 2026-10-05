import * as THREE from "three";

/** 程式產生的佔位模型；之後換成 glTF 時只要改這個檔。 */
export interface Model {
  object: THREE.Object3D;
  /** 名牌顯示的高度。 */
  labelHeight: number;
}

const mat = (color: number) => new THREE.MeshStandardMaterial({ color });

function mesh(geometry: THREE.BufferGeometry, color: number, x = 0, y = 0, z = 0): THREE.Mesh {
  const m = new THREE.Mesh(geometry, mat(color));
  m.position.set(x, y, z);
  m.castShadow = true;
  return m;
}

function player(isSelf: boolean): Model {
  const g = new THREE.Group();
  g.add(
    mesh(new THREE.CapsuleGeometry(0.35, 0.9, 4, 12), isSelf ? 0xd9a441 : 0x4a78b5, 0, 0.8, 0),
    // 鼻子：讓人看得出面向
    mesh(new THREE.BoxGeometry(0.15, 0.15, 0.2), 0x333333, 0, 1.25, 0.35),
  );
  return { object: g, labelHeight: 2 };
}

function rabbit(): Model {
  const g = new THREE.Group();
  g.add(
    mesh(new THREE.SphereGeometry(0.28, 12, 10), 0x9c9590, 0, 0.28, 0),
    mesh(new THREE.SphereGeometry(0.17, 10, 8), 0xa8a29c, 0, 0.45, 0.22),
    mesh(new THREE.CapsuleGeometry(0.04, 0.22, 2, 6), 0xb8b0aa, -0.07, 0.68, 0.2),
    mesh(new THREE.CapsuleGeometry(0.04, 0.22, 2, 6), 0xb8b0aa, 0.07, 0.68, 0.2),
  );
  return { object: g, labelHeight: 1.1 };
}

function chicken(): Model {
  const g = new THREE.Group();
  g.add(
    mesh(new THREE.SphereGeometry(0.25, 12, 10), 0xf2efe6, 0, 0.3, 0),
    mesh(new THREE.SphereGeometry(0.13, 10, 8), 0xf2efe6, 0, 0.55, 0.15),
    mesh(new THREE.BoxGeometry(0.04, 0.1, 0.12), 0xd23b2f, 0, 0.7, 0.15),
    mesh(new THREE.ConeGeometry(0.04, 0.1, 6), 0xe8a020, 0, 0.55, 0.3).rotateX(Math.PI / 2),
  );
  return { object: g, labelHeight: 1.1 };
}

function wolf(): Model {
  const g = new THREE.Group();
  g.add(
    mesh(new THREE.BoxGeometry(0.45, 0.45, 1.1), 0x55585e, 0, 0.6, 0),
    mesh(new THREE.BoxGeometry(0.35, 0.35, 0.45), 0x5f6268, 0, 0.8, 0.7),
    mesh(new THREE.ConeGeometry(0.07, 0.18, 4), 0x45474c, -0.1, 1.05, 0.65),
    mesh(new THREE.ConeGeometry(0.07, 0.18, 4), 0x45474c, 0.1, 1.05, 0.65),
  );
  for (const [x, z] of [[-0.15, 0.4], [0.15, 0.4], [-0.15, -0.4], [0.15, -0.4]]) {
    g.add(mesh(new THREE.BoxGeometry(0.12, 0.4, 0.12), 0x45474c, x, 0.2, z));
  }
  return { object: g, labelHeight: 1.6 };
}

function shopkeeper(): Model {
  const g = new THREE.Group();
  g.add(
    mesh(new THREE.CapsuleGeometry(0.42, 0.8, 4, 12), 0x8a5a3c, 0, 0.82, 0),
    // 圍裙
    mesh(new THREE.BoxGeometry(0.62, 0.6, 0.1), 0xe8dcc0, 0, 0.75, 0.38),
    // 瓜皮帽
    mesh(new THREE.SphereGeometry(0.28, 12, 8, 0, Math.PI * 2, 0, Math.PI / 2), 0x2f2a3a, 0, 1.5, 0),
  );
  return { object: g, labelHeight: 2.1 };
}

function elder(): Model {
  const g = new THREE.Group();
  g.add(
    // 灰色長袍
    mesh(new THREE.ConeGeometry(0.5, 1.4, 12), 0x8d8f99, 0, 0.7, 0),
    mesh(new THREE.SphereGeometry(0.24, 12, 10), 0xe0c4a8, 0, 1.55, 0),
    // 白鬍子
    mesh(new THREE.ConeGeometry(0.16, 0.4, 8), 0xf4f4f0, 0, 1.3, 0.16).rotateX(Math.PI),
    // 竹杖
    mesh(new THREE.CylinderGeometry(0.03, 0.03, 1.7), 0xb59a5a, 0.45, 0.85, 0.1),
  );
  return { object: g, labelHeight: 2.1 };
}

function unknown(): Model {
  return { object: mesh(new THREE.BoxGeometry(0.6, 0.6, 0.6), 0x8a4fbf, 0, 0.3, 0), labelHeight: 1.2 };
}

const NPC_MODELS: Record<string, () => Model> = {
  rabbit,
  chicken,
  wolf,
  "shopkeeper-chen": shopkeeper,
  "hunter-li": shopkeeper,
  "village-elder": elder,
};

/**
 * 地上的物品：統一用小布袋表示，顏色依物品 id 決定（同一種物品永遠同色）。
 * 內層 "bob" 物件會在 world-view 裡上下浮動、旋轉，讓掉落物比較顯眼。
 */
export function createItemModel(templateId: string): Model {
  let hash = 0;
  for (const ch of templateId) {
    hash = (hash * 31 + ch.charCodeAt(0)) | 0;
  }
  const color = new THREE.Color().setHSL(((hash >>> 0) % 360) / 360, 0.55, 0.55);
  const bob = new THREE.Group();
  bob.name = "bob";
  const bag = new THREE.Mesh(new THREE.SphereGeometry(0.22, 12, 10), new THREE.MeshStandardMaterial({
    color,
    emissive: color,
    emissiveIntensity: 0.25,
  }));
  bag.scale.y = 0.8;
  bag.castShadow = true;
  const knot = mesh(new THREE.CylinderGeometry(0.06, 0.09, 0.12, 8), 0x6b4a2b, 0, 0.2, 0);
  bob.add(bag, knot);
  bob.position.y = 0.3;
  return { object: bob, labelHeight: 0.85 };
}

/** 區域出口：地上的光圈加一道半透明光柱。內層 "bob" 會在 world-view 裡緩慢旋轉。 */
export function createPortalModel(): Model {
  const g = new THREE.Group();
  const glow = new THREE.MeshBasicMaterial({ color: 0x9fe3ff, transparent: true, opacity: 0.35, depthWrite: false });
  const column = new THREE.Mesh(new THREE.CylinderGeometry(1.1, 1.1, 3.2, 24, 1, true), glow);
  column.position.y = 1.6;
  const ring = new THREE.Mesh(
    new THREE.RingGeometry(0.9, 1.3, 32),
    new THREE.MeshBasicMaterial({ color: 0xc8f1ff, transparent: true, opacity: 0.8, side: THREE.DoubleSide }),
  );
  ring.rotation.x = -Math.PI / 2;
  ring.position.y = 0.03;
  const bob = new THREE.Group();
  bob.name = "bob";
  bob.add(column);
  g.add(ring, bob);
  return { object: g, labelHeight: 3.6 };
}

export function createModel(model: string, isSelf: boolean): Model {
  if (model === "player") {
    return player(isSelf);
  }
  return (NPC_MODELS[model] ?? unknown)();
}
