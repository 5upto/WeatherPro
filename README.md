# WeatherPro

A modern weather application built with Streamlit that provides real-time weather information and forecasts.

## Features

- Real-time weather data from OpenWeatherMap API
- Interactive weather visualization using Plotly
- User-friendly interface with Streamlit
- Historical weather data analysis
- Weather forecasting capabilities

## Prerequisites

- Python 3.8 or higher
- Internet connection for API access

## Installation

1. Clone the repository
2. Install dependencies:
   ```bash
   pip install -r requirements.txt
   ```

## Configuration

1. Create a `.env` file in the root directory with your OpenWeatherMap API key:
   ```
   OPENWEATHER_API_KEY=your_api_key_here
   ```

2. The backend server runs on `http://localhost:8080` by default

## Running the Application

1. Start the backend server (if not already running)
2. Run the Streamlit app:
   ```bash
   streamlit run ui/app.py
   ```

The application will be available at `http://localhost:8501`

## Project Structure

```
WeatherPro/
├── ui/
│   └── app.py           # Main Streamlit application
├── requirements.txt     # Python dependencies
├── README.md           # Project documentation
└── screenshots/        # Application screenshots
```

## Screenshots

![Weather Dashboard](screenshots/home.png)
![Current Weather](screenshots/current.png)
![Weather Forecast](screenshots/hourfore.png)
![5-Day Forecast ](screenshots/forecast.png)
![alert](screenshots/alert.png)

## Technologies Used

- Frontend: Streamlit
- Data Visualization: Plotly
- HTTP Requests: Requests
- Data Processing: Pandas
- Backend: Vert.x with Gradle
- API: OpenWeatherMap

## Backend Architecture

The backend is built using Vert.x, a high-performance reactive framework for the JVM. It's built with Gradle as the build system and runs on port 8080 by default.

### API Endpoints

#### Weather APIs

1. **Current Weather**
   - `GET /api/weather/current`
   - Parameters: `city` (required), `units` (optional)
   - Returns: Current weather conditions for a city

2. **Weather Forecast**
   - `GET /api/weather/forecast`
   - Parameters: `city` (required), `days` (optional, default=5)
   - Returns: 5-day weather forecast

3. **Historical Weather**
   - `GET /api/weather/historical`
   - Parameters: `city` (required), `start_date`, `end_date`
   - Returns: Historical weather data for a date range

4. **Weather Statistics**
   - `GET /api/weather/stats`
   - Parameters: `city` (required), `period` (day/week/month)
   - Returns: Weather statistics for the specified period

#### Authentication APIs

1. **Login**
   - `POST /api/auth/login`
   - Body: { "username": "string", "password": "string" }
   - Returns: JWT token

2. **Register**
   - `POST /api/auth/register`
   - Body: { "username": "string", "email": "string", "password": "string" }
   - Returns: Registration confirmation

3. **Refresh Token**
   - `POST /api/auth/refresh`
   - Headers: { "Authorization": "Bearer <refresh_token>" }
   - Returns: New access token

### API Response Format

All API responses follow this format:
```json
{
  "status": "success/error",
  "message": "Description of result",
  "data": { /* API-specific data */ }
}
```

## Building the Backend

To build and run the backend server:

1. Navigate to the server directory:
   ```bash
   cd server/app
   ```

2. Build the project:
   ```bash
   ./gradlew build
   ```

3. Run the server:
   ```bash
   ./gradlew run
   ```

The server will start on port 8080 by default.

## License

This project is licensed under the MIT License - see the LICENSE file for details.

## Contributing

1. Fork the repository
2. Create your feature branch (`git checkout -b feature/AmazingFeature`)
3. Commit your changes (`git commit -m 'Add some AmazingFeature'`)
4. Push to the branch (`git push origin feature/AmazingFeature`)
5. Open a Pull Request
