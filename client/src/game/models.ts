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

function unknown(): Model {
  return { object: mesh(new THREE.BoxGeometry(0.6, 0.6, 0.6), 0x8a4fbf, 0, 0.3, 0), labelHeight: 1.2 };
}

const NPC_MODELS: Record<string, () => Model> = { rabbit, chicken, wolf };

export function createModel(model: string, isSelf: boolean): Model {
  if (model === "player") {
    return player(isSelf);
  }
  return (NPC_MODELS[model] ?? unknown)();
}
