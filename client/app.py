import streamlit as st
import requests
import json
import pandas as pd
from datetime import datetime, timedelta
import plotly.graph_objects as go
import plotly.express as px
from typing import Dict, List, Optional
import streamlit_js_eval
from math import radians, cos, sin, asin, sqrt

BACKEND_URL = "http://localhost:8080"
OPENWEATHER_API_KEY = "ebb2b4f6eb7080ef10582240d598fdb7"

class WeatherApp:
    def __init__(self):
        self.init_session_state()
    
    def init_session_state(self):
        """Initialize session state variables"""
        if 'logged_in' not in st.session_state:
            st.session_state.logged_in = False
        if 'user_id' not in st.session_state:
            st.session_state.user_id = None
        if 'username' not in st.session_state:
            st.session_state.username = None
        if 'saved_locations' not in st.session_state:
            st.session_state.saved_locations = []
        if 'alerts' not in st.session_state:
            st.session_state.alerts = []
    
    def make_request(self, endpoint: str, method: str = "GET", data: Dict = None) -> Optional[Dict]:
        """Make HTTP request to backend"""
        try:
            url = f"{BACKEND_URL}{endpoint}"
            if method == "GET":
                response = requests.get(url)
            elif method == "POST":
                response = requests.post(url, json=data)
            elif method == "PUT":
                response = requests.put(url, json=data)
            elif method == "DELETE":
                response = requests.delete(url)
            
            if response.status_code == 200:
                return response.json()
            else:
                st.error(f"Request failed: {response.status_code}")
                return None
        except requests.exceptions.RequestException as e:
            st.error(f"Network error: {str(e)}")
            return None
    
    def login_page(self):
        """User login/registration page"""
        st.title("🌤️ WeatherPro - Personal Weather Dashboard")

        tab1, tab2 = st.tabs(["Login", "Register"])

        with tab1:
            st.subheader("Login to Your Account")
            col = st.columns([2, 3, 2])[1]  # Center column
            with col:
                username = st.text_input("Username", key="login_username")
                password = st.text_input("Password", type="password", key="login_password")
                st.markdown("<br>", unsafe_allow_html=True)
                if st.button("Login", key="login_btn"):
                    if username and password:
                        response = self.make_request("/api/auth/login", "POST", {
                            "username": username,
                            "password": password
                        })
                        if response and response.get("success"):
                            st.session_state.logged_in = True
                            st.session_state.user_id = response.get("user_id")
                            st.session_state.username = username
                            st.success("Login successful!")
                            st.rerun()
                        else:
                            st.error("Invalid credentials")
                    else:
                        st.error("Please fill in all fields")

        with tab2:
            st.subheader("Create New Account")
            col = st.columns([2, 3, 2])[1]  # Center column
            with col:
                reg_username = st.text_input("Username", key="reg_username")
                reg_email = st.text_input("Email", key="reg_email")
                reg_password = st.text_input("Password", type="password", key="reg_password")
                reg_confirm = st.text_input("Confirm Password", type="password", key="reg_confirm")
                st.markdown("<br>", unsafe_allow_html=True)
                if st.button("Register", key="register_btn"):
                    if reg_username and reg_email and reg_password and reg_confirm:
                        if reg_password == reg_confirm:
                            response = self.make_request("/api/auth/register", "POST", {
                                "username": reg_username,
                                "email": reg_email,
                                "password": reg_password
                            })
                            if response and response.get("success"):
                                st.success("Registration successful! Please login.")
                            else:
                                st.error("Registration failed")
                        else:
                            st.error("Passwords don't match")
                    else:
                        st.error("Please fill in all fields")
    
    def get_user_location(self):
        """Get user's current location using browser geolocation and store in session_state"""
        if "user_lat" in st.session_state and "user_lon" in st.session_state:
            return st.session_state.user_lat, st.session_state.user_lon

        loc = streamlit_js_eval.get_geolocation()
        if loc and loc.get("coords"):
            st.session_state.user_lat = loc["coords"]["latitude"]
            st.session_state.user_lon = loc["coords"]["longitude"]
            return st.session_state.user_lat, st.session_state.user_lon
        return None, None

    def get_weather_data(self, location: str) -> Optional[Dict]:
        """Fetch weather data using Geocoding API, biasing by user location if available"""
        try:
        # get user location
            user_lat, user_lon = self.get_user_location()
        # geocode the location name
            geo_url = (
                f"http://api.openweathermap.org/geo/1.0/direct?q={location}&limit=5&appid={OPENWEATHER_API_KEY}"
            )
            geo_response = requests.get(geo_url)
            geo_data = geo_response.json()
            if geo_response.status_code != 200 or not geo_data:
                st.error(f"Location '{location}' not found. Please enter a valid city name.")
                return None

        # pick the closest result
            if user_lat is not None and user_lon is not None:
                def distance(lat1, lon1, lat2, lon2):
                    # haversine formula
                    dlat = radians(lat2 - lat1)
                    dlon = radians(lon2 - lon1)
                    a = sin(dlat/2)**2 + cos(radians(lat1)) * cos(radians(lat2)) * sin(dlon/2)**2
                    c = 2 * asin(sqrt(a))
                    r = 6371
                    return c * r
                closest = min(
                    geo_data,
                    key=lambda loc: distance(user_lat, user_lon, loc['lat'], loc['lon'])
                )
                selected = closest
            else:
                selected = geo_data[0]

            lat, lon = selected['lat'], selected['lon']
            display_name = f"{selected['name']}, {selected.get('state', '')}, {selected['country']}".replace(" ,", "")

        # current weather
            current_url = f"https://api.openweathermap.org/data/2.5/weather?lat={lat}&lon={lon}&appid={OPENWEATHER_API_KEY}&units=metric"
            current_response = requests.get(current_url)
            if current_response.status_code != 200:
                st.error(f"Weather data not found for {display_name}")
                return None
            current_data = current_response.json()

        # 5-day forecast
            forecast_url = f"https://api.openweathermap.org/data/2.5/forecast?lat={lat}&lon={lon}&appid={OPENWEATHER_API_KEY}&units=metric"
            forecast_response = requests.get(forecast_url)

            weather_data = {
                "current": {
                    "temp": current_data.get("main", {}).get("temp", 0),
                    "feels_like": current_data.get("main", {}).get("feels_like", 0),
                    "humidity": current_data.get("main", {}).get("humidity", 0),
                    "pressure": current_data.get("main", {}).get("pressure", 0),
                    "uvi": 0,  # UV index not available in free tier
                    "wind_speed": current_data.get("wind", {}).get("speed", 0),
                    "wind_deg": current_data.get("wind", {}).get("deg", 0),
                    "visibility": current_data.get("visibility", 10000),
                    "sunrise": current_data.get("sys", {}).get("sunrise", 0),
                    "sunset": current_data.get("sys", {}).get("sunset", 0),
                    "weather": current_data.get("weather", [{}])
                },
                "lat": lat,
                "lon": lon
            }

        # forecast data if available
            if forecast_response.status_code == 200:
                forecast_data = forecast_response.json()
                weather_data["hourly"] = forecast_data.get("list", [])

                daily_forecasts = []
                processed_dates = set()
                for item in forecast_data.get("list", []):
                    date = datetime.fromtimestamp(item["dt"]).date()
                    if date not in processed_dates and len(daily_forecasts) < 5:
                        daily_forecasts.append({
                            "dt": item["dt"],
                            "temp": {
                                "max": item["main"]["temp_max"],
                                "min": item["main"]["temp_min"]
                            },
                            "weather": item["weather"]
                        })
                        processed_dates.add(date)
                weather_data["daily"] = daily_forecasts

            weather_data["display_name"] = display_name

            return weather_data
        
        except Exception as e:
            st.error(f"Error fetching weather data: {str(e)}")
            return None
    
    def save_location(self, location: str, display_name: str = None):
        """Save a location to user's favorites"""
        response = self.make_request("/api/locations/save", "POST", {
            "user_id": st.session_state.user_id,
            "location": location,
            "display_name": display_name or location
        })
        if response and response.get("success"):
            st.success(f"Location '{location}' saved!")
            self.load_saved_locations()
        else:
            st.error("Failed to save location")
    
    def load_saved_locations(self):
        """Load user's saved locations"""
        response = self.make_request(f"/api/locations/{st.session_state.user_id}")
        if response:
            st.session_state.saved_locations = response.get("locations", [])
    
    def create_weather_chart(self, forecast_data: List[Dict]) -> go.Figure:
        """Create temperature forecast chart"""
        if not forecast_data:
            return None
        
        times = [datetime.fromtimestamp(item['dt']) for item in forecast_data[:24]]  # Next 24 hours
        temps = [item['main']['temp'] for item in forecast_data[:24]]
        feels_like = [item['main']['feels_like'] for item in forecast_data[:24]]
        
        fig = go.Figure()
        fig.add_trace(go.Scatter(
            x=times, y=temps,
            mode='lines+markers',
            name='Temperature',
            line=dict(color='#FF6B6B', width=3)
        ))
        fig.add_trace(go.Scatter(
            x=times, y=feels_like,
            mode='lines',
            name='Feels Like',
            line=dict(color='#4ECDC4', width=2, dash='dash')
        ))
        
        fig.update_layout(
            title="24-Hour Temperature Forecast",
            xaxis_title="Time",
            yaxis_title="Temperature (°C)",
            template="plotly_white",
            height=400
        )
        return fig
    
    def display_weather_card(self, weather_data: Dict, location_name: str):
        """Display weather information in a card format"""
        current = weather_data.get('current', {})
        lat = weather_data.get('lat')
        lon = weather_data.get('lon')

        col1, col2, col3 = st.columns([2, 1, 1])

        with col1:
            # tooltip to the location marker
            if lat is not None and lon is not None:
                st.markdown(
                    f"""<span title="Lat: {lat}, Lon: {lon}">📍</span> <b>{location_name}</b>""",
                    unsafe_allow_html=True
                )
            else:
                st.subheader(f"📍 {location_name}")
            temp = current.get('temp', 0)
            feels_like = current.get('feels_like', 0)
            description = current.get('weather', [{}])[0].get('description', '').title()

            st.markdown(f"""
            ### 🌡️ {temp:.1f}°C
            **Feels like:** {feels_like:.1f}°C  
            **Condition:** {description}
            """)
        
        with col2:
            humidity = current.get('humidity', 0)
            pressure = current.get('pressure', 0)
            uv_index = current.get('uvi', 0)
            
            st.markdown(f"""
            **💧 Humidity:** {humidity}%  
            **📊 Pressure:** {pressure} hPa  
            **☀️ UV Index:** {uv_index if uv_index > 0 else 'N/A'}
            """)
        
        with col3:
            wind_speed = current.get('wind_speed', 0)
            wind_deg = current.get('wind_deg', 0)
            visibility = current.get('visibility', 0) / 1000
            
            st.markdown(f"""
            **💨 Wind:** {wind_speed:.1f} m/s  
            **🧭 Direction:** {wind_deg}°  
            **👁️ Visibility:** {visibility:.1f} km
            """)
        
        # sun times
        if current.get('sunrise', 0) > 0 and current.get('sunset', 0) > 0:
            sunrise = datetime.fromtimestamp(current.get('sunrise', 0))
            sunset = datetime.fromtimestamp(current.get('sunset', 0))
            
            col1, col2 = st.columns(2)
            with col1:
                st.info(f"🌅 **Sunrise:** {sunrise.strftime('%H:%M')}")
            with col2:
                st.info(f"🌇 **Sunset:** {sunset.strftime('%H:%M')}")
    
    def weather_alerts_section(self):
        """Display and manage weather alerts"""
        st.subheader("⚠️ Weather Alerts")
        
        # add new alert
        with st.expander("Add New Alert"):
            if st.session_state.saved_locations:
                alert_location = st.selectbox(
                    "Select Location",
                    options=[loc['display_name'] for loc in st.session_state.saved_locations]
                )
                alert_type = st.selectbox(
                    "Alert Type",
                    options=["Temperature", "Rain", "Wind", "Humidity"]
                )
                
                col1, col2 = st.columns(2)
                with col1:
                    condition = st.selectbox("Condition", options=["Above", "Below"])
                with col2:
                    threshold = st.number_input("Threshold Value", min_value=0.0)
                
                if st.button("Create Alert"):
                    response = self.make_request("/api/alerts/create", "POST", {
                        "user_id": st.session_state.user_id,
                        "location": alert_location,
                        "alert_type": alert_type.lower(),
                        "condition": condition.lower(),
                        "threshold": threshold
                    })
                    if response and response.get("success"):
                        st.success("Alert created successfully!")
            else:
                st.info("Please save some locations first to create alerts.")
        
        # display active alerts
        alerts_response = self.make_request(f"/api/alerts/{st.session_state.user_id}")
        if alerts_response:
            alerts = alerts_response.get("alerts", [])
            if alerts:
                for alert in alerts:
                    with st.container():
                        col1, col2, col3 = st.columns([3, 1, 1])
                        with col1:
                            st.write(f"📍 **{alert['location']}** - {alert['alert_type'].title()} {alert['condition']} {alert['threshold']}")
                        with col2:
                            if alert.get('triggered'):
                                st.error("🚨 ACTIVE")
                            else:
                                st.success("✅ Monitoring")
                        with col3:
                            if st.button("Delete", key=f"del_alert_{alert['id']}"):
                                self.make_request(f"/api/alerts/{alert['id']}", "DELETE")
                                st.rerun()
            else:
                st.info("No active alerts. Create one above!")
    
    def main_dashboard(self):
        """Main weather dashboard"""
        st.title(f"🌤️ Welcome back, {st.session_state.username}!")
    
        # sidebar for navigation and settings
        with st.sidebar:
            st.subheader("⚙️ Settings")
        
        # load saved locations
            self.load_saved_locations()
        
        # location search
            st.subheader("🔍 Search Location")
            search_location = st.text_input("Enter city name")
            get_weather_clicked = st.button("Get Weather")
        
        # always try to get user location (will prompt once)
            user_lat, user_lon = self.get_user_location()
        
            if get_weather_clicked:
                if search_location:
                    if user_lat is None or user_lon is None:
                        st.info("Requesting your location... Please allow location access and click 'Get Weather' again.")
                        st.stop()
                    with st.spinner("Fetching weather data..."):
                        weather_data = self.get_weather_data(search_location)
                        if weather_data:
                            st.session_state.current_weather = weather_data
                            st.session_state.current_location = weather_data.get("display_name", search_location)
                            st.success(f"Weather data loaded for {st.session_state.current_location}")
            
            # save current location
            if st.button("💾 Save Current Location"):
                if hasattr(st.session_state, 'current_location'):
                    self.save_location(st.session_state.current_location)
            
            # saved locations
            st.subheader("📌 Saved Locations")
            if st.session_state.saved_locations:
                for i, location in enumerate(st.session_state.saved_locations):
                    col1, col2 = st.columns([3, 1])
                    with col1:
                        if st.button(location['display_name'], key=f"loc_{i}"):
                            with st.spinner(f"Loading weather for {location['display_name']}..."):
                                weather_data = self.get_weather_data(location['location'])
                                if weather_data:
                                    st.session_state.current_weather = weather_data
                                    st.session_state.current_location = location['display_name']
                    with col2:
                        if st.button("🗑️", key=f"del_loc_{i}"):
                            self.make_request(f"/api/locations/{location['id']}", "DELETE")
                            self.load_saved_locations()
                            st.rerun()
            else:
                st.info("No saved locations yet")
            
            # logout
            if st.button("🚪 Logout"):
                st.session_state.logged_in = False
                st.session_state.user_id = None
                st.session_state.username = None
                st.rerun()
        
        # main content area
        tabs = st.tabs(["🌦️ Current Weather", "📈 Forecast", "⚠️ Alerts"])
        
        with tabs[0]:
            if hasattr(st.session_state, 'current_weather') and st.session_state.current_weather:
                self.display_weather_card(
                    st.session_state.current_weather,
                    st.session_state.current_location
                )
                
                # Additional weather details
                st.subheader("📊 Detailed Information")
                current = st.session_state.current_weather.get('current', {})
                
                col1, col2, col3, col4 = st.columns(4)
                with col1:
                    st.metric("Temperature", f"{current.get('temp', 0):.1f}°C")
                with col2:
                    st.metric("Humidity", f"{current.get('humidity', 0)}%")
                with col3:
                    st.metric("Wind Speed", f"{current.get('wind_speed', 0):.1f} m/s")
                with col4:
                    st.metric("Pressure", f"{current.get('pressure', 0)} hPa")
                
            else:
                st.info("👆 Search for a location to see weather data")
                
                # Show example search
                st.markdown("### 🔍 Try searching for:")
                example_cities = ["London", "New York", "Tokyo", "Paris", "Sydney"]
                cols = st.columns(len(example_cities))
                for i, city in enumerate(example_cities):
                    with cols[i]:
                        if st.button(city, key=f"example_{city}"):
                            with st.spinner(f"Loading weather for {city}..."):
                                weather_data = self.get_weather_data(city)
                                if weather_data:
                                    st.session_state.current_weather = weather_data
                                    st.session_state.current_location = city
                                    st.rerun()
        
        with tabs[1]:
            if hasattr(st.session_state, 'current_weather') and st.session_state.current_weather:
                forecast_data = st.session_state.current_weather.get('hourly', [])
                if forecast_data:
                    chart = self.create_weather_chart(forecast_data)
                    if chart:
                        st.plotly_chart(chart, use_container_width=True)
                    
                    # 5-day forecast
                    st.subheader("5-Day Forecast")
                    daily_data = st.session_state.current_weather.get('daily', [])
                    
                    if daily_data:
                        for day in daily_data:
                            date = datetime.fromtimestamp(day['dt']).strftime('%A, %B %d')
                            temp_max = day['temp']['max']
                            temp_min = day['temp']['min']
                            description = day['weather'][0]['description'].title()
                            
                            with st.container():
                                col1, col2, col3 = st.columns([2, 1, 2])
                                with col1:
                                    st.write(f"**{date}**")
                                with col2:
                                    st.write(f"🌡️ {temp_max:.1f}°/{temp_min:.1f}°C")
                                with col3:
                                    st.write(f"☁️ {description}")
                                st.divider()
                    else:
                        st.info("5-day forecast data not available")
                else:
                    st.info("Forecast data not available")
            else:
                st.info("👆 Search for a location to see forecast data")
        
        with tabs[2]:
            self.weather_alerts_section()
    
    def run(self):
        """Main application runner"""
        st.set_page_config(
            page_title="WeatherPro",
            page_icon="🌤️",
            layout="wide",
            initial_sidebar_state="expanded"
        )
        
        # Custom CSS for better styling
        st.markdown("""
        <style>
        .main-header {
            background: linear-gradient(90deg, #667eea 0%, #764ba2 100%);
            padding: 1rem;
            border-radius: 10px;
            color: white;
            text-align: center;
            margin-bottom: 2rem;
        }
        .weather-card {
            background: #f8f9fa;
            padding: 1rem;
            border-radius: 10px;
            border-left: 4px solid #007bff;
            margin: 1rem 0;
        }
        .metric-card {
            background: white;
            padding: 1rem;
            border-radius: 8px;
            box-shadow: 0 2px 4px rgba(0,0,0,0.1);
            text-align: center;
        }
        </style>
        """, unsafe_allow_html=True)
        
        if not st.session_state.logged_in:
            self.login_page()
        else:
            self.main_dashboard()

if __name__ == "__main__":
    app = WeatherApp()
    app.run()