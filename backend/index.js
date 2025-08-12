const express = require('express');
require('dotenv').config();
const cors = require('cors');
const { GoogleGenerativeAI } = require('@google/generative-ai');
const admin = require('firebase-admin');

// --- Firebase Admin SDK 초기화 ---
try {
  // Vercel 환경 변수에서 서비스 계정 키를 파싱
  const serviceAccount = JSON.parse(process.env.FIREBASE_SERVICE_ACCOUNT);
  
  admin.initializeApp({
    credential: admin.credential.cert(serviceAccount),
    projectId: process.env.FIREBASE_PROJECT_ID,
  });
  
  console.log('Firebase Admin SDK 초기화 성공');
} catch (error) {
  console.error('Firebase Admin SDK 초기화 실패:', error.message);
  // 초기화 실패 시 AI 기능도 비활성화되도록 처리할 수 있음
}

const db = admin.firestore(); // Firestore 인스턴스 가져오기
const app = express();
const port = process.env.PORT || 3001;

// CORS 설정
app.use(cors({
  origin: process.env.CORS_ORIGIN ? process.env.CORS_ORIGIN.split(',') : '*',
  methods: ['GET', 'POST', 'DELETE', 'OPTIONS'],
  allowedHeaders: ['Content-Type', 'Authorization']
}));
app.options('*', cors());
app.use(express.json());

// --- 재료 API 엔드포인트 (Firestore 사용) ---

// 모든 재료 가져오기 (GET /api/ingredients)
app.get('/api/ingredients', async (req, res) => {
  try {
    const ingredientsCollection = db.collection('ingredients');
    const snapshot = await ingredientsCollection.get();
    if (snapshot.empty) {
      return res.json([]);
    }
    const ingredients = snapshot.docs.map(doc => ({ id: doc.id, ...doc.data() }));
    res.json(ingredients);
  } catch (error) {
    console.error('Error fetching ingredients:', error);
    res.status(500).json({ error: 'Failed to read ingredients.' });
  }
});

// 새 재료 추가하기 (POST /api/ingredients)
app.post('/api/ingredients', async (req, res) => {
  try {
    const { name, expiryDate } = req.body;
    if (!name || !expiryDate) {
      return res.status(400).json({ error: 'Ingredient name and expiry date are required.' });
    }

    const newIngredient = { name, expiryDate };
    const docRef = await db.collection('ingredients').add(newIngredient);

    res.status(201).json({ id: docRef.id, ...newIngredient });
  } catch (error) {
    console.error('Error adding ingredient:', error);
    res.status(500).json({ error: 'Failed to add ingredient.' });
  }
});

// 재료 삭제하기 (DELETE /api/ingredients/:id)
app.delete('/api/ingredients/:id', async (req, res) => {
  try {
    const { id } = req.params;
    if (!id) {
      return res.status(400).json({ error: 'Ingredient ID is required.' });
    }
    
    const docRef = db.collection('ingredients').doc(id);
    const doc = await docRef.get();

    if (!doc.exists) {
      return res.status(404).json({ error: 'Ingredient not found.' });
    }

    await docRef.delete();
    res.status(204).send();
  } catch (error) {
    console.error('Error deleting ingredient:', error);
    res.status(500).json({ error: 'Failed to delete ingredient.' });
  }
});


// --- 레시피 추천 API (Firestore 사용) ---
let genAI = null;
if (process.env.API_KEY) {
  try {
    genAI = new GoogleGenerativeAI(process.env.API_KEY);
  } catch (e) {
    console.error('Generative AI 초기화 실패:', e.message);
  }
} else {
  console.warn('경고: API_KEY 환경변수가 설정되지 않았습니다. /api/recommend 사용 불가.');
}

app.post('/api/recommend', async (req, res) => {
  if (!genAI) {
    return res.status(503).json({ error: 'AI 기능이 비활성화되었습니다. (API_KEY 미설정)' });
  }
  try {
    const snapshot = await db.collection('ingredients').get();
    if (snapshot.empty) {
        return res.status(400).json({ recommendation: '냉장고에 재료가 없어요. 먼저 재료를 추가해주세요!' });
    }
    const ingredients = snapshot.docs.map(doc => doc.data().name);

    const model = genAI.getGenerativeModel({ model: process.env.GEMINI_MODEL || 'gemini-1.5-flash' });
    const prompt = `현재 냉장고에 있는 재료: ${ingredients.join(', ')}\n이 재료들을 활용할 수 있는 요리 1~3개와 간단한 설명, 필요한 추가 재료(있다면)를 제시하세요.`;
    const result = await model.generateContent(prompt);
    const response = await result.response;
    const text = response.text();
    res.json({ recommendation: text });
  } catch (error) {
    console.error('AI 추천 생성 중 오류:', error);
    res.status(500).json({ error: '레시피를 추천받는 데 실패했습니다.' });
  }
});

// 헬스 체크 & 환경 상태 노출 (민감 정보 제외)
app.get('/healthz', (req, res) => {
  res.json({
    ok: true,
    aiEnabled: !!genAI,
    firebaseInitialized: admin.apps.length > 0, // Firebase 초기화 상태 확인
    timestamp: new Date().toISOString()
  });
});

if (require.main === module) {
  app.listen(port, () => {
    console.log(`Server listening on :${port}`);
  });
}

module.exports = app;
