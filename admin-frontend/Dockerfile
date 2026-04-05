# Use pre-built dist from local (avoid Docker network issues)
FROM nginx:alpine
COPY admin-frontend/dist /usr/share/nginx/html
COPY admin-frontend/nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
