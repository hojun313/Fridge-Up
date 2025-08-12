import React, { useState, useEffect, useMemo } from 'react';
import './App.css';



function App() {
  // 상태 관리
  const [ingredients, setIngredients] = useState([]); // 재료 목록
  const [newIngredientName, setNewIngredientName] = useState(''); // 새 재료 이름
  const [newIngredientExpiry, setNewIngredientExpiry] = useState(''); // 새 재료 소비기한
  const [recommendation, setRecommendation] = useState(''); // AI 추천 레시피
  const [loading, setLoading] = useState(false); // 로딩 상태 (AI 추천)
  const [error, setError] = useState(null); // 에러 메시지

  // 컴포넌트가 처음 렌더링될 때 재료 목록을 불러옴
  useEffect(() => {
    fetchIngredients();
  }, []);

  // 백엔드에서 재료 목록을 가져오는 함수
  const fetchIngredients = async () => {
    try {
      setError(null);
  const response = await fetch(`/api/ingredients`);
      if (!response.ok) throw new Error('재료를 불러오는 데 실패했습니다.');
      const data = await response.json();
      setIngredients(data);
    } catch (err) {
      setError(err.message);
      console.error(err);
    }
  };

  // 새 재료를 추가하는 함수
  const handleAddIngredient = async (e) => {
    e.preventDefault(); // 폼 제출 기본 동작 방지
    if (!newIngredientName || !newIngredientExpiry) {
      setError('재료 이름과 소비기한을 모두 입력해주세요.');
      return;
    }

    const ingredientData = { 
      name: newIngredientName, 
      expiryDate: newIngredientExpiry 
    };

    console.log('서버로 보내는 데이터:', ingredientData); // [디버깅] 보내는 데이터 확인

    try {
      setError(null);
  const response = await fetch(`/api/ingredients`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(ingredientData),
      });

      console.log('서버 응답:', response); // [디버깅] 전체 응답 확인

      if (!response.ok) {
        const errorData = await response.json().catch(() => ({})); // 응답 본문이 JSON이 아닐 수도 있음
        console.error('서버 오류 응답:', errorData); // [디버깅] 오류 내용 확인
        throw new Error(errorData.error || '재료 추가에 실패했습니다.');
      }
      
      // 입력 필드 초기화 및 재료 목록 다시 불러오기
      setNewIngredientName('');
      setNewIngredientExpiry('');
      fetchIngredients(); 
    } catch (err) {
      setError(err.message);
      console.error(err);
    }
  };

  // 재료를 삭제하는 함수
  const handleDeleteIngredient = async (id) => {
    if (!window.confirm('정말로 이 재료를 삭제하시겠습니까?')) return;
    try {
      setError(null);
  const response = await fetch(`/api/ingredients/${id}`, {
        method: 'DELETE',
      });
      if (!response.ok) throw new Error('재료 삭제에 실패했습니다.');
      fetchIngredients(); // 재료 목록 다시 불러오기
    } catch (err) {
      setError(err.message);
      console.error(err);
    }
  };

  // 레시피를 추천받는 함수
  const handleGetRecommendation = async () => {
    setLoading(true);
    setRecommendation('');
    setError(null);
    try {
  const response = await fetch(`/api/recommend`, { method: 'POST' });
      const data = await response.json();
      if (response.status !== 200) {
        throw new Error(data.recommendation || data.error || '레시피 추천에 실패했습니다.');
      }
      setRecommendation(data.recommendation);
    } catch (err) {
      setError(err.message);
      console.error(err);
    } finally {
      setLoading(false);
    }
  };

  // 소비기한에 따라 재료를 정렬하고 스타일을 결정하는 로직
  const sortedIngredients = useMemo(() => {
    const today = new Date();
    today.setHours(0, 0, 0, 0); // 시간은 비교에서 제외

    return [...ingredients]
      .map(ing => {
        const expiryDate = new Date(ing.expiryDate);
        const diffDays = Math.ceil((expiryDate - today) / (1000 * 60 * 60 * 24));
        let status = 'fresh';
        if (diffDays < 0) status = 'expired';
        else if (diffDays <= 3) status = 'nearing-expiry';
        return { ...ing, status, diffDays };
      })
      .sort((a, b) => new Date(a.expiryDate) - new Date(b.expiryDate));
  }, [ingredients]);

  return (
    <div className="App">
      <header>
        <h1>Fridge-Up</h1>
        <p>냉장고 속 재료로 최고의 요리를 만나보세요!</p>
      </header>
      <main>
        <div className="ingredient-section">
          <h2>내 냉장고</h2>
          <form onSubmit={handleAddIngredient} className="ingredient-form">
            <input 
              type="text" 
              placeholder="재료 이름 (예: 우유)" 
              value={newIngredientName}
              onChange={(e) => setNewIngredientName(e.target.value)}
            />
            <input 
              type="date" 
              value={newIngredientExpiry}
              onChange={(e) => setNewIngredientExpiry(e.target.value)}
            />
            <button type="submit">추가</button>
          </form>
          <ul className="ingredient-list">
            {sortedIngredients.map(ing => (
              <li key={ing.id} className={`ingredient-item ${ing.status}`}>
                <div className="ingredient-info">
                  <span className="name">{ing.name}</span>
                  <span className="expiry">소비기한: {ing.expiryDate}</span>
                  {ing.status === 'expired' && <span className="tag expired-tag">기한만료</span>}
                  {ing.status === 'nearing-expiry' && <span className="tag nearing-tag">곧 만료 ({ing.diffDays}일 남음)</span>}
                </div>
                <button onClick={() => handleDeleteIngredient(ing.id)} className="delete-btn">삭제</button>
              </li>
            ))}
          </ul>
        </div>
        <div className="recipe-section">
          <h2>오늘의 추천 요리</h2>
          <button onClick={handleGetRecommendation} disabled={loading || ingredients.length === 0}>
            {loading ? '레시피 찾는 중...' : (ingredients.length === 0 ? '재료를 먼저 추가해주세요' : '레시피 추천받기')}
          </button>
          {error && <p className="error-message">오류: {error}</p>}
          {recommendation && (
            <div className="recommendation">
              <h3>추천 레시피:</h3>
              <pre>{recommendation}</pre>
            </div>
          )}
        </div>
      </main>
    </div>
  );
}

export default App;